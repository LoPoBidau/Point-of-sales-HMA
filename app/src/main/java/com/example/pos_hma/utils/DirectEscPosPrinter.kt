package com.example.pos_hma.print

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.nio.charset.Charset
import java.util.UUID

object DirectEscPosPrinter {

    // UUID SPP standar untuk printer thermal Bluetooth
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    /**
     * Kirim teks ESC/POS ke printer ber-MAC [macAddress]
     * Gunakan teks dari ReceiptFormatter.buildForPrinter(...).
     *
     * Tidak ada konsep "paper size" di sini; printer akan print sepanjang data,
     * lalu kita feed & (kalau ada cutter) potong.
     */
    fun print(
        context: Context,
        macAddress: String,
        text: String,
        onSuccess: () -> Unit = {},
        onError: (Throwable) -> Unit = {}
    ) {
        if (Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED) {
            onError(SecurityException("Izin BLUETOOTH_CONNECT belum diberikan")); return
        }

        Thread {
            try {
                val adapter = BluetoothAdapter.getDefaultAdapter()
                    ?: throw IllegalStateException("Bluetooth tidak tersedia")
                val device = adapter.getRemoteDevice(macAddress)
                val socket = device.createRfcommSocketToServiceRecord(SPP_UUID)

                adapter.cancelDiscovery()
                socket.connect()
                val os = socket.outputStream

                // --- Init & Codepage (coba 1252 dulu; jika karakter aneh, coba CP437/ISO-8859-1) ---
                os.write(byteArrayOf(0x1B, 0x40))            // ESC @
                os.write(byteArrayOf(0x1B, 0x61, 0x00))      // ESC a 0 -> align left (biar teks yang kita format dipakai)
                os.write(byteArrayOf(0x1B, 0x74, 0x10))      // ESC t 16 -> Windows-1252

                // Kirim dalam potongan kecil untuk printer yang buffer-nya kecil
                val data = text.replace("\n", "\r\n").toByteArray(Charset.forName("windows-1252"))
                val chunk = 512
                var off = 0
                while (off < data.size) {
                    val end = (off + chunk).coerceAtMost(data.size)
                    os.write(data, off, end - off)
                    os.flush()
                    off = end
                    Thread.sleep(10)
                }

                // Feed beberapa baris + partial cut (jika ada cutter)
                os.write(byteArrayOf(0x0A, 0x0A, 0x0A))      // feed 3 baris
                os.write(byteArrayOf(0x1D, 0x56, 0x42, 0x00))// GS V 66 0 (partial cut)

                os.flush()
                os.close()
                socket.close()

                Handler(Looper.getMainLooper()).post(onSuccess)
            } catch (t: Throwable) {
                Handler(Looper.getMainLooper()).post { onError(t) }
            }
        }.start()
    }
}
