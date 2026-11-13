package com.jiva.assistance.platform.contacts

import android.content.ContentResolver
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract

/** Uses Android's contact picker, so Jiva does not need broad READ_CONTACTS permission. */
object ContactPicker {
    fun pickPhoneIntent(): Intent = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)

    fun readSelectedPhone(resolver: ContentResolver, uri: Uri): PickedContact? {
        var cursor: Cursor? = null
        return try {
            cursor = resolver.query(
                uri,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                ),
                null,
                null,
                null,
            )
            if (cursor != null && cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                if (nameIndex < 0 || numberIndex < 0) return null
                val name = cursor.getString(nameIndex).orEmpty()
                val number = cursor.getString(numberIndex).orEmpty()
                number.takeIf(String::isNotBlank)?.let { PickedContact(name.ifBlank { it }, it) }
            } else null
        } catch (_: SecurityException) {
            null
        } finally {
            cursor?.close()
        }
    }
}

data class PickedContact(val displayName: String, val phoneNumber: String)
