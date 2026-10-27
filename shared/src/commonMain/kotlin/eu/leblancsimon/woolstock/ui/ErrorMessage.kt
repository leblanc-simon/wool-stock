package eu.leblancsimon.woolstock.ui

import androidx.compose.runtime.Composable
import eu.leblancsimon.woolstock.data.api.ApiException
import eu.leblancsimon.woolstock.resources.Res
import eu.leblancsimon.woolstock.resources.error_email_not_allowed
import eu.leblancsimon.woolstock.resources.error_email_taken
import eu.leblancsimon.woolstock.resources.error_forbidden
import eu.leblancsimon.woolstock.resources.error_insufficient_stock
import eu.leblancsimon.woolstock.resources.error_invalid_response
import eu.leblancsimon.woolstock.resources.error_inventory_closed
import eu.leblancsimon.woolstock.resources.error_last_admin
import eu.leblancsimon.woolstock.resources.error_network
import eu.leblancsimon.woolstock.resources.error_server
import eu.leblancsimon.woolstock.resources.error_unauthorized
import eu.leblancsimon.woolstock.resources.error_unexpected
import eu.leblancsimon.woolstock.resources.validation_count
import eu.leblancsimon.woolstock.resources.validation_ean_format
import eu.leblancsimon.woolstock.resources.validation_ean_required
import eu.leblancsimon.woolstock.resources.validation_email
import eu.leblancsimon.woolstock.resources.validation_name_required
import eu.leblancsimon.woolstock.resources.validation_quantity
import eu.leblancsimon.woolstock.resources.validation_recipient_required
import eu.leblancsimon.woolstock.resources.validation_url
import eu.leblancsimon.woolstock.resources.validation_url_https
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** Message d'erreur résolu au moment de l'affichage, dans la langue courante. */
sealed interface ErrorMessage {
    data class Resource(val id: StringResource, val args: List<Any> = emptyList()) : ErrorMessage

    data class Plural(val id: PluralStringResource, val count: Int) : ErrorMessage

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
suspend fun ErrorMessage.load(): String = when (this) {
    is ErrorMessage.Resource -> getString(id, *args.toTypedArray())
    is ErrorMessage.Plural -> getPluralString(id, count, count)
    is ErrorMessage.Text -> text
}

/** Les codes d'erreur de l'API sont résolus dans les ressources de l'app quand elle les connaît. */
fun Throwable.toErrorMessage(): ErrorMessage = when (this) {
    is ApiException.Network -> ErrorMessage.Resource(Res.string.error_network)
    is ApiException.InvalidAddress ->
        ErrorMessage.Resource(if (cleartext) Res.string.validation_url_https else Res.string.validation_url)
    is ApiException.InvalidResponse -> ErrorMessage.Resource(Res.string.error_invalid_response)
    is ApiException.Server -> when (error?.code) {
        "error.insufficient_stock" -> ErrorMessage.Plural(Res.plurals.error_insufficient_stock, error.available ?: 0)
        "validation.ean_required" -> ErrorMessage.Resource(Res.string.validation_ean_required)
        "validation.ean_format" -> ErrorMessage.Resource(Res.string.validation_ean_format)
        "validation.quantity" -> ErrorMessage.Resource(Res.string.validation_quantity)
        "validation.recipient_required" -> ErrorMessage.Resource(Res.string.validation_recipient_required)
        "validation.count" -> ErrorMessage.Resource(Res.string.validation_count)
        "error.inventory_closed" -> ErrorMessage.Resource(Res.string.error_inventory_closed)
        "error.email_not_allowed" -> ErrorMessage.Resource(Res.string.error_email_not_allowed)
        "error.email_taken" -> ErrorMessage.Resource(Res.string.error_email_taken)
        "error.forbidden" -> ErrorMessage.Resource(Res.string.error_forbidden)
        "error.last_admin" -> ErrorMessage.Resource(Res.string.error_last_admin)
        "error.unauthorized" -> ErrorMessage.Resource(Res.string.error_unauthorized)
        "validation.email" -> ErrorMessage.Resource(Res.string.validation_email)
        "validation.name_required" -> ErrorMessage.Resource(Res.string.validation_name_required)
        else -> error?.message?.takeIf { it.isNotBlank() }?.let { ErrorMessage.Text(it) }
            ?: ErrorMessage.Resource(Res.string.error_server, listOf(status))
    }
    else -> ErrorMessage.Resource(Res.string.error_unexpected)
}
