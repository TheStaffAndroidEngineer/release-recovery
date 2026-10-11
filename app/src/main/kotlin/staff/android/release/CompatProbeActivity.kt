package staff.android.release

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/** Separate lab instrument. The normal shopping Activity handles insets explicitly. */
class CompatProbeActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(TextView(this).apply { text = "Platform compatibility probe"; textSize = 24f; setBackgroundColor(0xffdfebff.toInt()) })
    }
}
