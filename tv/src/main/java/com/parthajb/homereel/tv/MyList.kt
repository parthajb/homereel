package com.parthajb.homereel.tv

import android.content.Context
import android.os.Handler
import android.widget.Button
import android.widget.Toast
import java.util.concurrent.ExecutorService

/** The ♥ My List button: filled and "In My List" when it's there, an outline and "My List" when it isn't. */
fun showMyList(button: Button, on: Boolean) {
    button.setText(if (on) R.string.my_list_remove else R.string.my_list_add)
    button.setCompoundDrawablesRelativeWithIntrinsicBounds(
        if (on) R.drawable.ic_heart_small else R.drawable.ic_heart_outline_small, 0, 0, 0,
    )
}

/** Adds to or removes from the profile's My List on the phone, updating the button straight away. */
fun toggleMyList(
    context: Context,
    store: ServerStore,
    worker: ExecutorService,
    main: Handler,
    key: String,
    on: Boolean,
    button: Button,
    saved: (Boolean) -> Unit,
) {
    showMyList(button, on)
    worker.execute {
        val result = runCatching { store.api().setInList(key, on) }
        main.post {
            result.onSuccess { saved(on) }.onFailure {
                showMyList(button, !on)
                Toast.makeText(context, it.message, Toast.LENGTH_LONG).show()
            }
        }
    }
}
