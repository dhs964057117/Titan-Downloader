import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import java.io.File

object FileOpenerUtil {
    fun openFile(context: Context, filePath: String) {
        val authority = "${context.packageName}.provider"
        val uri = if (File(filePath).exists()) {
            FileProvider.getUriForFile(context, authority, File(filePath))
        } else {
            filePath.toUri()
        }
        val mimeType = context.contentResolver.getType(uri)
        if (uri != null) {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                context.startActivity(intent)
            } catch (ignore: Exception) {

            }
        }
    }
}