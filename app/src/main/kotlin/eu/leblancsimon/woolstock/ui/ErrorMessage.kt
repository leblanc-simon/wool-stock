package eu.leblancsimon.woolstock.ui

import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import eu.leblancsimon.woolstock.R
import eu.leblancsimon.woolstock.data.api.ApiException

/** Message d'erreur résolu au moment de l'affichage, dans la langue courante. */
sealed interface ErrorMessage {
    data class Resource(@StringRes val id: Int, val args: List<Any> = emptyList()) : ErrorMessage

    data class Plural(@PluralsRes val id: Int, val count: Int) : ErrorMessage

    /** Message déjà traduit par le serveur, pour un code que l'app ne connaît pas. */
    data class Text(val text: String) : ErrorMessage
}

@Composable
fun ErrorMessage.text(): String = when (this) {
    is ErrorMessage.Resource -> stringResource(id, *args.toTypedArray())
    is ErrorMessage.Plural -> pluralStringResource(id, count, count)
    is ErrorMessage.Text -> text
}

/** Le même message hors composition, pour une barre de messages lancée depuis une coroutine. */
fun ErrorMessage.text(resources: Resources): String = when (this) {
    is ErrorMessage.Resource -> resources.getString(id, *args.toTypedArray())
    is ErrorMessage.Plural -> resources.getQuantityString(id, count, count)
    is ErrorMessage.Text -> text
}

/** Les codes d'erreur de l'API sont résolus dans les ressources de l'app quand elle les connaît. */
fun Throwable.toErrorMessage(): ErrorMessage = when (this) {
    is ApiException.Network -> ErrorMessage.Resource(R.string.error_network)
    is ApiException.Server -> when (error?.code) {
        "error.insufficient_stock" -> ErrorMessage.Plural(R.plurals.error_insufficient_stock, error.available ?: 0)
        "validation.ean_required" -> ErrorMessage.Resource(R.string.validation_ean_required)
        "validation.ean_format" -> ErrorMessage.Resource(R.string.validation_ean_format)
        "validation.quantity" -> ErrorMessage.Resource(R.string.validation_quantity)
        "validation.recipient_required" -> ErrorMessage.Resource(R.string.validation_recipient_required)
        "validation.count" -> ErrorMessage.Resource(R.string.validation_count)
        "error.inventory_closed" -> ErrorMessage.Resource(R.string.error_inventory_closed)
        "error.email_not_allowed" -> ErrorMessage.Resource(R.string.error_email_not_allowed)
        "error.email_taken" -> ErrorMessage.Resource(R.string.error_email_taken)
        "error.forbidden" -> ErrorMessage.Resource(R.string.error_forbidden)
        "error.last_admin" -> ErrorMessage.Resource(R.string.error_last_admin)
        "error.unauthorized" -> ErrorMessage.Resource(R.string.error_unauthorized)
        "validation.email" -> ErrorMessage.Resource(R.string.validation_email)
        "validation.name_required" -> ErrorMessage.Resource(R.string.validation_name_required)
        else -> error?.message?.takeIf { it.isNotBlank() }?.let { ErrorMessage.Text(it) }
            ?: ErrorMessage.Resource(R.string.error_server, listOf(status))
    }
    else -> ErrorMessage.Resource(R.string.error_unexpected)
}
