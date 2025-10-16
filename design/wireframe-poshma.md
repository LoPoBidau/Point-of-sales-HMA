# POSHMA Wireframe Blueprint (Figma-ready)

Dokumen ini merangkum struktur wireframe untuk aplikasi POSHMA berdasarkan kode sumber saat ini. Susunan konten mengikuti praktik terbaik Figma sehingga dapat direalisasikan menjadi file `.fig` dengan cepat.

---

## 1. Struktur File Figma

- **Page 01 – Foundations**
  - Frame `Color & Type`: palet warna, tipografi (Material 3, keluarga font `Poppins` atau `Inter` sebagai pengganti Roboto).
  - Frame `Components`: tombol utama/sekunder, chip filter, kartu ringkasan, list item, bottom navigation, dialog.
- **Page 02 – Auth & Entry**
  - Frame `Login`
  - Frame `Role Gate / Splash`
- **Page 03 – Admin (Kasir)**
  - Frame `Catalog`
  - Frame `Cart`
  - Frame `Payment`
  - Frame `Receipt`
  - Frame `Goods`
  - Frame `Report`
  - Pop-up `Profile & Logout`
- **Page 04 – Super Admin (Owner)**
  - Frame `Dashboard`
  - Frame `Inventory`
  - Frame `Adjust Request`
  - Frame `Report`
  - Frame `Product List`
  - Frame `Product Form (Modal Sheet)`
  - Frame `Supplier`
  - Frame `Category`
  - Frame `Notification`
- **Page 05 – States & Overlays**
  - Empty state, loading state, error toast, snackbar.

Setiap frame menggunakan ukuran dasar Android: **Sw 360 x 800 px** (Auto Layout + grid 4 kolom, margin 16px, gutter 12px).

---

## 2. Komponen Dasar (Page 01)

- **Top App Bar**
  - Tinggi 56 px, leading icon (opsional), judul, action area (ikon profil/notifikasi).
  - Varian: *Default*, *Hidden (Receipt)*, *With Tabs*.
- **Bottom Navigation**
  - 4 item (Super Admin) / 3 item (Admin). Ikon Material + label.
- **Card Summary**
  - Auto Layout vertikal, padding 16, sudut 12, bayangan `elevation sm`.
  - Varian: *Metric*, *Highlight (accent border)*, *Alert (warning)*.
- **List Item**
  - Leading gambar/initial, judul, subjudul, badge status, trailing actions.
  - Varian: *Single line*, *Two lines*, *With switch*.
- **Filter Chips**
  - 32 px height, radius penuh, varian: *Default*, *Selected*, *Disabled*.
- **Buttons**
  - `Primary` (filled), `Secondary` (outlined), `Tertiary` (text).
- **Dialog / Bottom Sheet**
  - Header, konten dengan daftar/field, footer tindakan.

---

## 3. Auth & Entry (Page 02)

### Frame: `Login`
- **Layout**
  - Header logo (center) + nama brand (POSHMA).
  - Card form (Auto Layout vertical, padding 24): field email, password, tombol `Masuk`.
  - Link teks `Lupa kata sandi?`.
  - Footer: ilustrasi atau tagline (opsional).
- **Interaction notes**
  - Validasi inline (error helper text 12 px di bawah field).
  - CTA sekunder kecil: `Hubungi Owner` (text button) untuk bantuan.

### Frame: `Role Gate / Splash`
- Logo di tengah, spinner/loading, teks `Memeriksa role pengguna...`.
- Gunakan background gradien ringan sesuai warna primer POSHMA.

---

## 4. Admin (Kasir) Flow (Page 03)

### Frame: `Catalog`
- **App Bar**
  - Judul: `Katalog Barang`.
  - Action kanan: ikon keranjang dengan badge.
- **Konten**
  - Pencarian: TextField dengan ikon `filter` sebagai start icon.
  - Filter chips horizontal (scrollable) di bawah pencarian.
  - List produk (Recycler, list vertikal):
    - Gambar produk (64x64), info (nama, SKU, harga), stepper +/-.
  - Sticky cart bar di bawah: label `Sub Total`, total Rp, tombol `Lanjut` (full width).
- **States**
  - Loading skeleton list.
  - Empty: ilustrasi + teks `Belum ada produk`.

### Frame: `Cart`
- **App Bar**: `Keranjang`.
- **Konten**
  - Daftar item: nama, varian, qty stepper, subtotal.
  - Section ringkasan: `Diskon`, `Biaya Layanan` (toggle), `Catatan`.
  - CTA: `Lanjut Pembayaran` (primary).
- **Footer**: total besar, breakdown.

### Frame: `Payment`
- **App Bar** hidden (menurut kode).
- **Layout**
  - Card total tagihan (nominal besar).
  - Pilihan metode (Radio list: Tunai, Transfer, Qris).
  - Field `Nominal diterima` (untuk tunai) + auto hitung kembalian.
  - Opsional: toggle `Cetak struk`.
  - CTA: `Selesaikan Transaksi`.
- **Right/secondary panel** (sticky bottom sheet) menampilkan ringkasan item.

### Frame: `Receipt`
- Full-screen modal.
- Header: ikon sukses, teks `Transaksi Berhasil`.
- Konten: ringkasan struk (nama toko, daftar item, total, info pembayaran).
- CTA: `Bagikan`, `Cetak`, `Kembali ke Katalog`.

### Frame: `Goods`
- **App Bar**: `Stok Barang`.
- Tabs di dalam konten (chips) untuk filter `Semua`, `Hampir Habis`, `Non Aktif`.
- Daftar barang dengan badge stok (progress bar kecil).
- Floating Action Button `+ Barang Baru` (opsional sesuai role admin).

### Frame: `Report`
- Filter rentang tanggal (chip group: Hari ini, 7 hari, dst).
- Ringkasan kartu (Total Transaksi, Omzet, Rata-rata nota).
- Tabel/list transaksi histori dengan search dan quick filter (Dropdown `Metode`).
- Detail transaksi (side sheet) saat item dipilih.

### Overlay: `Profile & Logout`
- Dialog kecil (280 px) berisi email, role, tombol `Keluar`.

---

## 5. Super Admin Flow (Page 04)

### Frame: `Dashboard`
- **Top**
  - Greeting `Halo, Owner!`.
  - Chip filter rentang waktu (Hari ini, 7 hari, 30 hari, 90 hari) – Auto Layout horizontal.
- **Hero Cards (Grid 2 kolom)**
  - `Total Penjualan`, `Total Transaksi`, `Rata-rata Nota`, `Produk Terjual`.
- **Chart Section**
  - Line chart `Tren Pendapatan`.
  - Pie chart `Top 5 Produk`.
- **Today Summary Card** (hanya saat range `Hari Ini`): metrik `Transaksi`, `Pendapatan`, `Rata-rata`, `Refund`.
- **Pending Widget**
  - Card khusus `Permintaan Penyesuaian Stok` (count + CTA `Lihat`).

### Frame: `Inventory`
- Search bar, filter status (chip: `Masuk`, `Keluar`, `Penyesuaian`).
- Timeline list (card vertikal): tanggal, user, detail qty per item.
- Floating button `Tambah Penyesuaian` → membuka modal form.

### Frame: `Adjust Request`
- Tab switcher `Pending`, `Disetujui`, `Ditolak`.
- Setiap kartu: info gudang/toko, daftar item, tombol aksi `Setujui`/`Tolak`.
- Status badge warna berbeda.

### Frame: `Report`
- Header filter (rangetime, outlet, kategori).
- KPI cards + stacked percentage bar chart `Komposisi Penjualan`.
- Data table (scrollable) dengan kolom: `Tanggal`, `Transaksi`, `Omzet`, `Laba`, `Metode`.
- Export buttons (`Download CSV`, `Unduh PDF`).

### Frame: `Product List`
- Segment control `Barang` / `Jasa`.
- Search, filter dropdown kategori, toggle `Hanya Stok Rendah`.
- Grid kartu (2 kolom): thumbnail, nama, SKU, stok, badge status.
- Floating button `+ Produk Baru`.
- `Bulk Actions` bar muncul saat multi-select (hapus, arsip).

### Frame: `Product Form (Bottom Sheet)`
- Stepper: `Detail Utama` → `Harga & Stok` → `Gambar`.
- Field: nama, SKU, kategori (dropdown), satuan, harga beli/jual (rupiah formatter), stok awal, gambar (preview).
- Toggles: `Butuh persetujuan penyesuaian`, `Notif stok rendah`.
- Footer: `Batal`, `Simpan`.

### Frame: `Supplier`
- List card: nama supplier, kontak, tag kategori barang, tombol `Hubungi`.
- CTA: `Tambah Supplier`.

### Frame: `Category`
- List reorderable (drag handle).
- Item: nama kategori, badge jumlah produk, tombol `Edit`, `Nonaktifkan`.
- Modal kecil untuk tambah/edit.

### Frame: `Notification`
- App bar dengan tombol back (bottom nav disembunyikan).
- List notifikasi dengan icon status, judul, deskripsi pendek, timestamp.
- Filter chips: `Semua`, `Prioritas`, `Belum dibaca`.
- Bulk action: tombol `Tandai sudah dibaca`.

---

## 6. States & Overlays (Page 05)

- **Loading**
  - Skeleton card/list (bar abu-abu dengan animasi shimmer).
  - Spinner + teks `Memuat data...`.
- **Empty**
  - Ilustrasi sederhana (ikon outline), judul, sub copy, tombol tindakan.
- **Error**
  - Snackbar merah: `Gagal memuat data penjualan.` + aksi `Coba lagi`.
  - Dialog dengan CTA `Hubungi Dukungan`.
- **Offline**
  - Banner merah di atas: `Tidak ada akses internet` + ikon.

---

## 7. Style Guide Singkat

- **Palet**
  - `Primary` #1E40AF
  - `Primary Variant` #1D4ED8
  - `Secondary` #F97316
  - `Surface` #FFFFFF, `Surface Variant` #F2F4F7
  - `Success` #16A34A, `Warning` #FACC15, `Error` #DC2626
- **Typography**
  - Display: `32/40 Bold`
  - Heading: `24/32 SemiBold`
  - Subtitle: `18/24 Medium`
  - Body: `14/20 Regular`
  - Caption: `12/16 Regular`
  - Gunakan Auto Text Styles di Figma.
- **Iconography**
  - Gunakan Material Symbols Rounded 24 px.
  - Simpan sebagai component set (`/Icons/Outlined/...`).

---

## 8. Alur Navigasi (Flow Map)

- **Login → Role Gate**
  - Bila role `admin`: masuk ke `AdminCashierMain`.
  - Bila role `owner/superadmin`: masuk ke `SuperAdminMain`.
- **Admin**
  - Bottom Nav: Catalog ↔ Goods ↔ Report.
  - Catalog → Cart → Payment → Receipt → balik ke Catalog.
  - Report detail → modal detail transaksi.
- **Super Admin**
  - Bottom Nav: Dashboard ↔ Inventory ↔ Report ↔ Adjust Request.
  - Dashboard → Notification (app bar action).
  - Inventory/Product/Supplier/Category dapat diakses via tab atau ikon menu (sesuaikan bottom nav).

Di Figma, buat flow arrows (Prototype mode) untuk menggambarkan transisi utama di atas.

---

## 9. Tips Implementasi di Figma

1. **Gunakan Auto Layout** pada hampir semua frame agar mudah diresponsifkan.
2. **Varians Komponen**: siapkan set untuk tombol (`Primary`, `Secondary`, `Loading`), chips (`Default`, `Selected`, `Disabled`), list item (`Normal`, `With Badge`, `Error State`).
3. **Styles Global**: definisikan `Color Styles` dan `Text Styles` sebelum menggambar layar.
4. **Layout Grid**: set 4 kolom (margin 16, gutter 12) pada setiap frame layar utama.
5. **Prototyping**: koneksikan CTA utama untuk menunjukkan arus transaksi kasir dan flow manajemen owner.
6. **Document Notes**: gunakan sticky note / comment di Figma untuk catatan dev (mis. “Grafik menggunakan MPAndroidChart”).

---

## 10. Checklist Export

- [ ] Semua frame dinamai sesuai screen (mis. `Admin / Catalog`).
- [ ] Komponen global tersimpan di page `Foundations`.
- [ ] Flow kasir dan super admin terhubung di mode Prototype.
- [ ] State (loading/empty/error) tersedia untuk setiap modul data.
- [ ] Dokumentasi interaksi penting (tooltip/notes).

Dengan blueprint ini, desainer dapat langsung membangun file `.fig` yang konsisten dengan implementasi aplikasi POSHMA saat ini.

