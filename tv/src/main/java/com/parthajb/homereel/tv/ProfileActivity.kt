package com.parthajb.homereel.tv

import android.app.AlertDialog
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import com.parthajb.homereel.shared.Profile
import java.util.concurrent.Executors

/**
 * "Who's watching?" when the app opens (with more than one profile) and from Settings. Leaving a kids profile for a
 * grown-up one asks for the parent PIN, checked by the phone.
 */
class ProfileActivity : FragmentActivity() {
    private val store by lazy { ServerStore(this) }
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var row: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profiles)
        row = findViewById(R.id.profiles)
        worker.execute {
            val result = runCatching { store.api().profiles() }
            main.post {
                if (isDestroyed) return@post
                result.onSuccess { list ->
                    // With one profile there's nothing to choose.
                    if (list.profiles.size == 1 && !intent.getBooleanExtra(EXTRA_SWITCH, false)) {
                        choose(list.profiles.first())
                    } else {
                        show(list.profiles, list.pinSet)
                    }
                }.onFailure {
                    // Offline: carry on with whoever watched last; the home screen explains what's wrong.
                    Session.profileChosen = true
                    openHome()
                }
            }
        }
    }

    private fun show(profiles: List<Profile>, pinSet: Boolean) {
        row.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for (p in profiles) {
            val item = inflater.inflate(R.layout.profile_item, row, false)
            val avatar = item.findViewById<TextView>(R.id.avatar)
            avatar.text = p.name.take(1).uppercase()
            avatar.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(AVATAR_COLOURS[p.avatar % AVATAR_COLOURS.size])
            }
            item.findViewById<TextView>(R.id.name).text = p.name
            item.findViewById<View>(R.id.kids).visibility = if (p.kids) View.VISIBLE else View.GONE
            item.setOnFocusChangeListener { v, focused ->
                v.animate().scaleX(if (focused) 1.12f else 1f).scaleY(if (focused) 1.12f else 1f).setDuration(150).start()
            }
            item.setOnClickListener { pick(p, pinSet) }
            row.addView(item)
            if (p.id == store.profileId) item.requestFocus()
        }
        if (row.findFocus() == null) row.getChildAt(0)?.requestFocus()
    }

    private fun pick(p: Profile, pinSet: Boolean) {
        val leavingKids = store.profileKids && !p.kids && store.profileId != p.id
        if (leavingKids && pinSet) askPin { choose(p) } else choose(p)
    }

    private fun askPin(onOk: () -> Unit) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = getString(R.string.pin_hint)
            gravity = Gravity.CENTER
            textSize = 26f
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        fun submit() {
            val pin = input.text.toString()
            worker.execute {
                val result = runCatching { store.api().checkPin(pin) }
                main.post {
                    result.onSuccess { onOk() }.onFailure { Toast.makeText(this, it.message, Toast.LENGTH_LONG).show() }
                }
            }
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.pin_title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ -> submit() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        // The keyboard's Done key submits too: on a TV that's where the remote already is.
        input.setOnEditorActionListener { _, action, _ ->
            (action == EditorInfo.IME_ACTION_DONE).also {
                if (it) {
                    dialog.dismiss()
                    submit()
                }
            }
        }
        input.requestFocus()
    }

    private fun choose(p: Profile) {
        val changed = store.profileId != p.id
        store.setProfile(p.id, p.name, p.kids)
        Session.profileChosen = true
        if (changed) Images.clear()
        openHome()
    }

    private fun openHome() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SWITCH = "switch"

        /** The same twelve colours as the admin page, so a profile looks the same everywhere. */
        val AVATAR_COLOURS = intArrayOf(
            0xFFE50914.toInt(), 0xFFF5B301.toInt(), 0xFF16A34A.toInt(), 0xFF2563EB.toInt(), 0xFF9333EA.toInt(),
            0xFFDB2777.toInt(), 0xFF0891B2.toInt(), 0xFFEA580C.toInt(), 0xFF4F46E5.toInt(), 0xFF65A30D.toInt(),
            0xFFBE123C.toInt(), 0xFF0F766E.toInt(),
        )
    }
}

/** Whether a profile was picked since the app started: "Who's watching?" asks once per launch. */
object Session {
    var profileChosen = false
}
