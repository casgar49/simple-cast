package com.dupontgu.simplecast

import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadOptions
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.Session
import com.google.android.gms.cast.framework.SessionManager
import com.google.android.gms.cast.framework.SessionManagerListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL


internal const val TAG = "MAIN"
internal const val STREAM_ADDRESS_KEY = "streamAddressKey"

class MainActivity : AppCompatActivity() {
    private lateinit var castSessionManager: SessionManager
    private val sessionManagerListener: SessionManagerListener<Session> by lazy { SessionManagerListenerImpl() }
    private val mainScreen: ConstraintLayout by lazy { findViewById(R.id.main_screen) }
    private val currentlyPlaying: TextView by lazy { findViewById(R.id.currently_playing) }
    private val loading: FrameLayout by lazy { findViewById(R.id.loading) }
    private val add: Button by lazy { findViewById(R.id.add) }

    private val streamLocationField: EditText by lazy { findViewById(R.id.streamLocationField) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(mainScreen) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            // Apply the insets as a margin to the view. This solution sets
            // only the bottom, left, and right dimensions, but you can apply whichever
            // insets are appropriate to your layout. You can also update the view padding
            // if that's more appropriate.
            v.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = insets.left
                bottomMargin = insets.bottom
                rightMargin = insets.right
                topMargin = insets.top
            }

            // Return CONSUMED if you don't want want the window insets to keep passing
            // down to descendant views.
            WindowInsetsCompat.CONSUMED
        }
        castSessionManager = CastContext.getSharedInstance(this).sessionManager
        window.decorView.systemUiVisibility =View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR

        displayCurrentAddress()
        add.setOnClickListener {
            streamLocationField.text.takeIf { !it.isNullOrBlank() }?.toString()?.let { streamLocation ->
                saveStreamAddress(streamLocation)
            } ?: run {
                val builder = AlertDialog.Builder(this)
                builder.setMessage("Please enter valid stream address and restart your cast session")
                    .setNegativeButton("Ok") { dialog, _ ->
                        dialog.dismiss()
                    }
                builder.create().show()
            }
        }
    }

    private fun displayCurrentAddress(){
        val currentAddress = getSavedStreamAddress()
        currentlyPlaying.setVisible(true)
        currentAddress?.let {
            currentlyPlaying.text = "Saved address $currentAddress, Ready to cast!"
            add.text = "Update"
        }?:run {
            currentlyPlaying.text = "No address saved."
        }

    }

    override fun onResume() {
        super.onResume()
        castSessionManager.addSessionManagerListener(sessionManagerListener)
        // if the field is blank and we have a saved address, populate the field with it
        if (streamLocationField.text.isNullOrBlank()) {
            getSavedStreamAddress().takeIf { !it.isNullOrBlank() }?.let { streamLocationField.setText(it) }
        }
    }

    override fun onPause() {
        super.onPause()
        castSessionManager.removeSessionManagerListener(sessionManagerListener)
    }

    fun startCast() {
        getSavedStreamAddress()?.let { streamLocation ->
            val castSession = castSessionManager.currentCastSession ?: run {
                loading.setVisible(false)
                Toast.makeText(this, "CastSession is null", Toast.LENGTH_LONG).show()
                return
            }

            val audioMetadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK)
            Log.d(TAG, "Starting cast for: $streamLocation")
            val mediaInfo = MediaInfo.Builder(streamLocation)
                .setStreamType(MediaInfo.STREAM_TYPE_LIVE)
                .setContentType("audio/aac")
                .setMetadata(audioMetadata)
                .build()
            castSession.remoteMediaClient?.load(mediaInfo, MediaLoadOptions.Builder().build())

        }
    }

    private fun saveStreamAddress(address: String) {
        val flow = testClient(address)
        lifecycleScope.launch {
            flow.flowOn(Dispatchers.IO).collect{ result ->
                launch(Dispatchers.Main) {
                    if ( result is CastResult.Success){
                        displayCurrentAddress()
                        PreferenceManager.getDefaultSharedPreferences(this@MainActivity).edit().putString(STREAM_ADDRESS_KEY, address).apply()
                        invalidateOptionsMenu()
                    }else if (result is CastResult.Error){
                        val builder = AlertDialog.Builder(this@MainActivity)
                        builder.setMessage(result.error)
                            .setPositiveButton("Try again") { dialog, _ ->
                                saveStreamAddress(address)
                                dialog.dismiss()
                            }
                            .setNegativeButton("Cancel") { dialog, _ ->
                                dialog.dismiss()
                            }
                        builder.create().show()
                    }
                }

                }
        }


    }

    private fun testClient(client:String): Flow<CastResult<Unit>> {
        return flow {
            emit (try{
                val url = URL(client)
                val urlConnection =
                    withContext(Dispatchers.IO) {
                        url.openConnection()
                    } as HttpURLConnection
                try {
                    if (urlConnection.responseCode != 200){
                        CastResult.Error("Couldn't connect to the provided server. Make sure that the server exists and try again.")
                    }else{
                        CastResult.emptySuccess
                    }

                }
                catch (e:Throwable) {
                    CastResult.Error("Couldn't connect to the provided server. Make sure that the server exists and try again.")
                }finally {
                    urlConnection.disconnect()

                }
            }
            catch (e:Throwable) {
                CastResult.Error("Couldn't connect to the provided server. Make sure that the server exists and try again.")
            })

        }
    }

    private fun getSavedStreamAddress(): String? {
        return PreferenceManager.getDefaultSharedPreferences(this).getString(STREAM_ADDRESS_KEY, null)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        super.onCreateOptionsMenu(menu)
        menuInflater.inflate(R.menu.main, menu)
        CastButtonFactory.setUpMediaRouteButton(applicationContext, menu, R.id.media_route_menu_item)
        if (getSavedStreamAddress() == null){
            menu.findItem(R.id.media_route_menu_item).setVisible(false)

        }else{
            menu.findItem(R.id.media_route_menu_item).setVisible(true)
        }
        return true
    }

    private fun View.setVisible(isVisible:Boolean){
        visibility = if (isVisible){
            VISIBLE
        }else{
            GONE
        }
    }

    private inner class SessionManagerListenerImpl : SessionManagerListener<Session> {
        override fun onSessionStarting(session: Session) {
            Log.d(TAG, "Cast onSessionStarting")
        }

        override fun onSessionStarted(session: Session, sessionId: String) {
            Log.d(TAG, "Cast onSessionStarted")
            loading.setVisible(true)
            invalidateOptionsMenu()
            startCast()
            finishAction()
        }

        override fun onSessionStartFailed(session: Session, i: Int) {
            Log.d(TAG, "Cast onSessionStartFailed")
            finishAction()
        }

        override fun onSessionEnding(session: Session) {
            Log.d(TAG, "Cast onSessionEnding")
            finishAction()
        }

        override fun onSessionResumed(session: Session, wasSuspended: Boolean) {
            Log.d(TAG, "Cast onSessionResumed")
            invalidateOptionsMenu()
        }

        override fun onSessionResumeFailed(session: Session, i: Int) {
            Log.d(TAG, "Cast onSessionResumeFailed")
            finishAction()
        }

        override fun onSessionSuspended(session: Session, i: Int) {
            Log.d(TAG, "Cast onSessionSuspended")
        }

        override fun onSessionEnded(session: Session, error: Int) {
            Log.d(TAG, "Cast onSessionEnded")
            finishAction()
        }

        override fun onSessionResuming(session: Session, s: String) {
            Log.d(TAG, "Cast onSessionResuming")

        }


    }

    private fun finishAction(){
        loading.setVisible(false)
    }
}


