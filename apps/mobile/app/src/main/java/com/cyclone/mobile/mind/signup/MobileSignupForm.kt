package com.cyclone.mobile.mind.signup

import java.time.LocalDate

/** Schema-derived preflight; verification codes arrive during the run and never belong in a saved input row. */
object MobileSignupForm {
    fun fields(map: SignupMap): List<SignupField> = map.pages.filter { it.check == null }.flatMap { it.fields }
        .distinctBy { it.key }.filter { it.kind != SignupFieldKind.PHOTO }

    fun errors(map: SignupMap, values: Map<String, String>, passwordLength: Int, today: LocalDate = LocalDate.now()): List<String> = buildList {
        fields(map).forEach { field ->
            val value = values[field.key].orEmpty().trim()
            when {
                field.kind == SignupFieldKind.PASSWORD -> if (passwordLength !in 6..4_096) add("Enter a password of at least six characters.")
                field.required && value.isBlank() -> add("${field.label} is required.")
                value.length > 300 || value.any { it.code < 32 } -> add("${field.label} has an invalid value.")
                field.kind == SignupFieldKind.BIRTHDAY && value.isNotBlank() -> {
                    val date = runCatching { LocalDate.parse(value) }.getOrNull()
                    if (date == null || !date.isBefore(today) || date.year < 1900) add("Enter a valid date of birth as YYYY-MM-DD.")
                }
                field.kind == SignupFieldKind.PHONE && value.isNotBlank() -> if (!Regex("^\\+[0-9 ()-]{7,22}$").matches(value) || value.count(Char::isDigit) !in 7..15)
                    add("Enter your phone number with its country code.")
                field.kind == SignupFieldKind.USERNAME && value.isNotBlank() -> if (!Regex("^[A-Za-z0-9._]{1,30}$").matches(value))
                    add("Use up to 30 letters, numbers, periods or underscores for the username.")
                field.choices.isNotEmpty() && value.isNotBlank() && value !in field.choices -> add("Choose one of the listed ${field.label} options.")
            }
        }
    }

    fun plan(map: SignupMap, values: Map<String, String>): AccountSetupPlan {
        val plainKeys = fields(map).filter { it.kind != SignupFieldKind.PASSWORD }.map { it.key }.toSet()
        return AccountSetupPlan(map, values.filterKeys { it in plainKeys }.mapValues { it.value.trim() }, finalApproved = false)
    }
}
