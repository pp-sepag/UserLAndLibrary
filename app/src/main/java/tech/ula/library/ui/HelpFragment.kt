package tech.ula.library.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import kotlinx.android.synthetic.main.frag_help.*
import tech.ula.library.R

class HelpFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.frag_help, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        welcome_text.text = getString(R.string.welcome,getString(R.string.app_name))

        github_logo.setOnClickListener {
            val intent = Intent("android.intent.action.VIEW", Uri.parse("https://github.com/CypherpunkArmory/UserLAnd/issues"))
            startActivity(intent)
        }

        userland_logo.setOnClickListener {
            val intent = Intent("android.intent.action.VIEW", Uri.parse("https://userland.tech"))
            startActivity(intent)
        }
    }
}