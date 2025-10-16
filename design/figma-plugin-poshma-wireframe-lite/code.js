var FRAME_WIDTH = 360;
var FRAME_HEIGHT = 800;
var HORIZONTAL_GAP = 120;
var FONT = { family: "Roboto", style: "Regular" };

var screens = [
  {
    name: "Login",
    summary:
      "Header logo + brand, form email/password, helper link 'Lupa kata sandi?', CTA 'Masuk'.",
    flow: ["Masuk → Role Gate"]
  },
  {
    name: "Role Gate / Splash",
    summary:
      "Logo tengah, status 'Memeriksa role pengguna...', pilihan masuk sebagai Admin atau Owner.",
    flow: ["Admin → Catalog", "Owner → Dashboard", "Kembali → Login"]
  },
  {
    name: "Admin · Catalog",
    summary:
      "App bar dengan ikon keranjang, search + filter chips, daftar produk, sticky cart bar.",
    flow: ["Lanjut → Cart", "Bottom Nav → Goods/Report", "Profil → Dialog Logout"]
  },
  {
    name: "Admin · Cart",
    summary:
      "Daftar barang keranjang, ringkasan diskon/biaya layanan, CTA 'Lanjut Pembayaran'.",
    flow: ["Pembayaran → Payment", "Kembali → Catalog"]
  },
  {
    name: "Admin · Payment",
    summary:
      "Ringkasan total tagihan, opsi metode bayar (Tunai/Transfer/QRIS), input nominal diterima.",
    flow: ["Selesai → Receipt", "Batal → Cart"]
  },
  {
    name: "Admin · Receipt",
    summary:
      "Konfirmasi sukses transaksi, struk sederhana dengan opsi bagikan/cetak.",
    flow: ["Kembali → Catalog"]
  },
  {
    name: "Admin · Goods",
    summary:
      "Daftar stok barang, filter chip (Semua/Hampir Habis/Non Aktif), tombol tambah barang.",
    flow: ["Bottom Nav → Catalog/Report"]
  },
  {
    name: "Admin · Report",
    summary:
      "Filter rentang waktu, KPI cards, histori transaksi, detail drawer.",
    flow: ["Detail → Payment", "Bottom Nav → Catalog"]
  },
  {
    name: "Owner · Dashboard",
    summary:
      "Sapaan Owner, chip range waktu, ringkasan penjualan, grafik pendapatan & top produk, kartu pending stok.",
    flow: ["Bottom Nav → Inventory/Report/Adjust", "Notif → Notification"]
  },
  {
    name: "Owner · Inventory",
    summary:
      "Timeline pergerakan stok, filter status, tombol tambah penyesuaian.",
    flow: ["Tambah → Adjust Request", "Shortcut → Product"]
  },
  {
    name: "Owner · Report",
    summary:
      "Filter (tanggal/outlet/kategori), KPI overview, chart komposisi, tabel + export.",
    flow: ["Kelola Produk → Product", "Back → Dashboard"]
  },
  {
    name: "Owner · Adjust Request",
    summary:
      "Tab Pending/Disetujui/Ditolak, kartu permintaan stok dengan aksi Setujui/Tolak.",
    flow: ["Back → Dashboard", "Detail → Inventory"]
  },
  {
    name: "Owner · Product",
    summary:
      "Toggle Barang/Jasa, search & filter, grid kartu 2 kolom, tombol tambah, link supplier/kategori.",
    flow: ["Tambah → Product Form", "Supplier/Kategori → masing-masing modul"]
  },
  {
    name: "Owner · Product Form",
    summary:
      "Stepper Detail → Harga & Stok → Gambar, toggle notif stok rendah, tombol Simpan/Batal.",
    flow: ["Simpan → Product", "Batal → Product"]
  },
  {
    name: "Owner · Supplier",
    summary:
      "Daftar kartu supplier (nama, kontak, tag barang), CTA tambah supplier.",
    flow: ["Back → Product"]
  },
  {
    name: "Owner · Category",
    summary:
      "Daftar kategori reorderable, badge jumlah produk, modal tambah/edit.",
    flow: ["Back → Product"]
  },
  {
    name: "Owner · Notification",
    summary:
      "List notifikasi dengan status unread, filter chip (Semua/Prioritas/Belum dibaca), tombol tandai selesai.",
    flow: ["Back → Dashboard"]
  }
];

async function main() {
  await figma.loadFontAsync(FONT);

  var page = figma.currentPage;
  var x = 0;

  for (var i = 0; i < screens.length; i++) {
    var data = screens[i];

    var existing = page.findOne(function (node) {
      return node.type === "FRAME" && node.name === data.name;
    });

    var frame;
    if (existing) {
      frame = existing;
    } else {
      frame = figma.createFrame();
      frame.resize(FRAME_WIDTH, FRAME_HEIGHT);
      frame.name = data.name;
      frame.x = x;
      frame.y = 0;
      frame.fills = [{ type: "SOLID", color: { r: 1, g: 1, b: 1 } }];
      frame.strokeWeight = 1;
      frame.strokes = [{ type: "SOLID", color: { r: 0.85, g: 0.87, b: 0.93 } }];
      frame.cornerRadius = 24;
      frame.clipsContent = false;
      frame.layoutMode = "VERTICAL";
      frame.paddingLeft = 24;
      frame.paddingRight = 24;
      frame.paddingTop = 32;
      frame.paddingBottom = 32;
      frame.itemSpacing = 24;

      page.appendChild(frame);
      x += FRAME_WIDTH + HORIZONTAL_GAP;

      addText(frame, data.name, 22, { r: 0.1, g: 0.15, b: 0.25 });
      addText(frame, data.summary, 13, { r: 0.32, g: 0.35, b: 0.42 });

      if (data.flow && data.flow.length > 0) {
        var flowFrame = figma.createFrame();
        flowFrame.name = "Flow";
        flowFrame.layoutMode = "VERTICAL";
        flowFrame.counterAxisSizingMode = "AUTO";
        flowFrame.primaryAxisSizingMode = "AUTO";
        flowFrame.paddingLeft = 16;
        flowFrame.paddingRight = 16;
        flowFrame.paddingTop = 16;
        flowFrame.paddingBottom = 16;
        flowFrame.itemSpacing = 8;
        flowFrame.cornerRadius = 16;
        flowFrame.strokes = [{ type: "SOLID", color: { r: 0.82, g: 0.86, b: 0.94 } }];
        flowFrame.strokeWeight = 1;
        flowFrame.fills = [{ type: "SOLID", color: { r: 0.96, g: 0.97, b: 1 } }];
        frame.appendChild(flowFrame);

        addText(flowFrame, "Flow", 14, { r: 0.16, g: 0.2, b: 0.3 });

        for (var j = 0; j < data.flow.length; j++) {
          addText(flowFrame, "• " + data.flow[j], 12, { r: 0.3, g: 0.32, b: 0.4 });
        }
      }
    }
  }

  figma.closePlugin("Wireframe outline berhasil dibuat.");
}

function addText(parent, content, fontSize, color) {
  var text = figma.createText();
  text.fontName = FONT;
  text.fontSize = fontSize;
  text.lineHeight = { value: fontSize + 6, unit: "PIXELS" };
  text.characters = content;
  text.fills = [{ type: "SOLID", color: color }];
  parent.appendChild(text);
  return text;
}

main().catch(function (error) {
  var message = error && error.message ? error.message : String(error);
  figma.closePlugin("Gagal membuat wireframe: " + message);
});

