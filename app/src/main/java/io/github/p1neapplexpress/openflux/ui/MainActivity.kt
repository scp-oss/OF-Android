package io.github.p1neapplexpress.openflux.ui

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import io.github.p1neapplexpress.openflux.R
import io.github.p1neapplexpress.openflux.event.EventBus
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rootView = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // Only the top (status bar) is handled here. The bottom system
            // bar inset (nav bar / gesture area) is handled locally by
            // whichever screen has bottom-anchored content that needs it
            // (see TunnelsFragment.applyBottomBarInsets) — applying it both
            // here as padding AND there as margin would double-count it.
            view.updatePadding(top = bars.top)
            // Not CONSUMED: pass the real insets through so descendant
            // listeners (like that one) still get an accurate reading
            // instead of a zeroed-out value.
            insets
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        supportActionBar?.hide()

        setContentView(R.layout.activity_main)
        supportFragmentManager.beginTransaction()
            .replace(R.id.main, MainFragment(), "")
            .commit()


        lifecycleScope.launch {
            EventBus.events.collect { ev ->
                supportFragmentManager.fragments.forEach { f ->
                    if (f is BaseFragment) f.onNewEvent(ev)
                }
            }
        }
    }

}
