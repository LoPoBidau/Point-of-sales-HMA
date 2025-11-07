package com.example.pos_hma.utils

import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FirebaseFirestoreException.Code.*
import java.io.IOException
import java.net.SocketTimeoutException

fun Throwable?.toUserMessage(defaultMessage: String): String {
    return when (this) {
        is FirebaseFirestoreException -> when (code) {
            PERMISSION_DENIED -> "Akses ditolak. Hubungi admin untuk mendapatkan izin."
            UNAVAILABLE -> "Layanan sedang tidak tersedia. Periksa koneksi internet dan coba lagi."
            DEADLINE_EXCEEDED, ABORTED -> "Permintaan terlalu lama. Coba ulang beberapa saat lagi."
            else -> defaultMessage
        }
        is FirebaseNetworkException,
        is SocketTimeoutException,
        is IOException -> "Koneksi internet bermasalah. Periksa jaringan dan coba lagi."
        is FirebaseAuthInvalidCredentialsException -> "Data login tidak valid. Periksa kembali dan coba lagi."
        is FirebaseAuthInvalidUserException -> "Akun tidak ditemukan atau dinonaktifkan. Silakan masuk ulang."
        is FirebaseAuthException -> "Autentikasi gagal. Silakan coba masuk kembali."
        else -> defaultMessage
    }
}
