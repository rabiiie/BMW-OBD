package com.rabie.bmwobd.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

fun shareText(context: Context, subject: String, text: String) {
    val intent = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, subject)
        .putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(intent, subject))
}

/** Abre el navegador con una busqueda. La consulta sale del movil hacia el buscador. */
fun searchWeb(context: Context, query: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(query)))
    runCatching { context.startActivity(intent) }
}

/**
 * Abre Waze. Si la app esta en pantalla partida, lo abre en la otra mitad. Devuelve false si no
 * esta instalado.
 */
fun openWaze(context: Context): Boolean {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("waze://"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
    return runCatching { context.startActivity(intent) }.isSuccess
}

/** Comparte un fichero privado de la app a traves del FileProvider declarado en el manifiesto. */
fun shareFile(context: Context, file: File, mimeType: String) {
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
    val intent = Intent(Intent.ACTION_SEND)
        .setType(mimeType)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, file.name))
}
