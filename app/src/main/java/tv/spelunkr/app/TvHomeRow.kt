package tv.spelunkr.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.tvprovider.media.tv.PreviewChannel
import androidx.tvprovider.media.tv.PreviewChannelHelper
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * The "SPELUNKR" row on the Android TV / Google TV home screen: one card per channel, favorites
 * first, each with the channel's pixel logo. Picking a card opens the app tuned to that channel.
 *
 * Cards show the channel, not the tape on now: the site's schedule (ads, bumpers, blocks) is
 * worked out by its own engine, and a guess made here would drift from the guide.
 */
object TvHomeRow {
    const val PREFS = "spelunkr"
    const val KEY_FAVORITES = "favorites"
    const val SCHEME = "spelunkr"
    private const val KEY_CHANNEL_ID = "home_row_channel_id"
    private const val LINEUP_URL = "https://spelunkr.tv/broadcast/lineup.json"
    private const val TAG = "SpelunkrHomeRow"

    private val worker = Executors.newSingleThreadExecutor()

    fun syncInBackground(context: Context, done: (() -> Unit)? = null) {
        val app = context.applicationContext
        worker.execute {
            try {
                sync(app)
            } catch (e: Exception) {
                Log.w(TAG, "home row not updated", e)
            } finally {
                done?.invoke()
            }
        }
    }

    private fun supported(context: Context) =
        context.packageManager.resolveContentProvider(TvContractCompat.AUTHORITY, 0) != null

    private fun sync(context: Context) {
        if (!supported(context)) {
            Log.i(TAG, "no TV home screen provider here; skipping")
            return
        }
        val channels = fetchChannels()
        if (channels.isEmpty()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val favorites = parseFavorites(prefs.getString(KEY_FAVORITES, null))
        val helper = PreviewChannelHelper(context)

        var rowId = prefs.getLong(KEY_CHANNEL_ID, -1L)
        if (rowId == -1L || helper.getPreviewChannel(rowId) == null) {
            val row = PreviewChannel.Builder()
                .setDisplayName(context.getString(R.string.app_name))
                .setDescription(context.getString(R.string.home_row_description))
                .setAppLinkIntentUri(Uri.parse(launchIntent(context, null).toUri(Intent.URI_INTENT_SCHEME)))
                .setLogo(logo(context))
                .build()
            rowId = helper.publishChannel(row)
            prefs.edit().putLong(KEY_CHANNEL_ID, rowId).apply()
            // The app's first row may ask to be shown; Google TV can still leave it to the viewer.
            TvContractCompat.requestChannelBrowsable(context, rowId)
        }

        context.contentResolver.delete(TvContractCompat.buildPreviewProgramsUriForChannel(rowId), null, null)
        val ordered = channels.sortedWith(compareBy({ it.id !in favorites }, { it.dial }))
        ordered.forEachIndexed { index, channel ->
            val favorite = channel.id in favorites
            val program = PreviewProgram.Builder()
                .setChannelId(rowId)
                .setType(TvContractCompat.PreviewPrograms.TYPE_CHANNEL)
                .setTitle((if (favorite) "★ " else "") + "CH ${channel.dial} · ${channel.name}")
                .setDescription(channel.description)
                .setPosterArtUri(Uri.parse("https://spelunkr.tv/brand/pixel/png/${channel.id}-256.png"))
                .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_1_1)
                .setLive(true)
                .setWeight(ordered.size - index)
                .setIntent(launchIntent(context, channel.id))
                .setInternalProviderId(channel.id)
                .build()
            helper.publishPreviewProgram(program)
        }
        Log.i(TAG, "home row $rowId: ${ordered.size} channels, ${favorites.size} favorites")
    }

    private fun launchIntent(context: Context, channel: String?): Intent =
        Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            if (channel != null) data = Uri.parse("$SCHEME://tune/$channel")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

    private data class Channel(val id: String, val name: String, val dial: Int, val description: String)

    private fun fetchChannels(): List<Channel> {
        val connection = URL(LINEUP_URL).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        return try {
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val list = json.getJSONArray("channels")
            (0 until list.length()).map { list.getJSONObject(it) }
                .filter { !it.optBoolean("debugOnly") && it.has("dial") }
                .map { Channel(it.getString("id"), it.getString("name"), it.getInt("dial"), it.optString("description")) }
        } finally {
            connection.disconnect()
        }
    }

    private fun parseFavorites(json: String?): Set<String> = try {
        val array = JSONArray(json ?: "[]")
        (0 until array.length()).map { array.getString(it) }.toSet()
    } catch (_: Exception) {
        emptySet()
    }

    private fun logo(context: Context): Bitmap {
        val drawable = ContextCompat.getDrawable(context, R.mipmap.ic_launcher)!!
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, 256, 256)
        drawable.draw(Canvas(bitmap))
        return bitmap
    }

    /** Google TV asks new apps to set up their home rows right after install, before the first launch. */
    class InitializeReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val pending = goAsync()
            syncInBackground(context) { pending.finish() }
        }
    }
}
