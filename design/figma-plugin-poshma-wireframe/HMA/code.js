const FONT_PRIMARY = { family: "Inter", style: "Regular" };
const FONT_FALLBACK = { family: "Roboto", style: "Regular" };
let activeFont = FONT_PRIMARY;
const FRAME_WIDTH = 360;
const FRAME_HEIGHT = 800;
const HORIZONTAL_GAP = 120;

const COLORS = {
  primary: { r: 0.1176, g: 0.2510, b: 0.6863 }, // #1E40AF
  onPrimary: { r: 1, g: 1, b: 1 },
  sectionBorder: { r: 0.82, g: 0.87, b: 0.95 },
  sectionFill: { r: 0.96, g: 0.97, b: 0.99 },
  textStrong: { r: 0.12, g: 0.16, b: 0.24 },
  textMuted: { r: 0.29, g: 0.32, b: 0.39 }
};

const frameRegistry = new Map();
const pendingPrototypeLinks = [];

const pagesData = [
  {
    name: "01 Foundations",
    frames: [
      {
        key: "foundations-color-type",
        name: "Color & Type",
        sections: [
          {
            title: "Brand Palette",
            notes:
              "• Primary: #1E40AF\n• Primary Variant: #1D4ED8\n• Secondary: #F97316\n• Surface: #FFFFFF / #F2F4F7\n• Success: #16A34A, Warning: #FACC15, Error: #DC2626"
          },
          {
            title: "Typography",
            notes:
              "• Display 32/40 Bold\n• Heading 24/32 SemiBold\n• Subtitle 18/24 Medium\n• Body 14/20 Regular\n• Caption 12/16 Regular\nGunakan Inter atau Poppins sesuai Material 3."
          },
          {
            title: "Iconography",
            notes:
              "• Material Symbols Rounded 24 px\n• Simpan component set outlined & filled\n• Konsistensi stroke 2 px dan warna on-surface."
          }
        ]
      },
      {
        key: "foundations-components",
        name: "Components",
        sections: [
          {
            title: "Buttons",
            notes:
              "• Primary (filled), Secondary (outlined), Tertiary (text)\n• Sertakan state default, hover, disabled, loading."
          },
          {
            title: "Chips",
            notes:
              "• Filter chip tinggi 32 px, radius penuh\n• Varian: default, selected, disabled\n• Gunakan Auto Layout horizontal scroll."
          },
          {
            title: "Cards",
            notes:
              "• Metric, highlight (accent border), alert (warning)\n• Radius 12 px, padding 16, ikon status opsional."
          },
          {
            title: "Navigation",
            notes:
              "• Top app bar default / hidden / tabs\n• Bottom nav 3 item (Admin) & 4 item (Owner)\n• Badge notifikasi untuk role Owner."
          }
        ]
      }
    ]
  },
  {
    name: "02 Auth & Entry",
    frames: [
      {
        key: "auth-login",
        name: "Login",
        sections: [
          {
            title: "Header",
            notes: "• Logo POSHMA center\n• Tagline singkat opsional."
          },
          {
            title: "Form",
            notes:
              "• TextField email & password (Material)\n• Helper text untuk validasi\n• Tombol 'Masuk' primary penuh."
          },
          {
            title: "Helper Actions",
            notes:
              "• Link 'Lupa kata sandi?'\n• Text button 'Hubungi Owner'."
          },
          {
            title: "Footer",
            notes:
              "• Ilustrasi atau tagline\n• Info kontak dukungan (opsional)."
          }
        ],
        prototypeLinks: [
          {
            label: "Masuk",
            target: "auth-role-gate",
            transition: "DISSOLVE"
          }
        ]
      },
      {
        key: "auth-role-gate",
        name: "Role Gate / Splash",
        sections: [
          {
            title: "Logo & Brand",
            notes: "• Logo 96 px di tengah\n• Latar gradien warna primer."
          },
          {
            title: "Progress Feedback",
            notes:
              "• Spinner Material\n• Label 'Memeriksa role pengguna...'"
          },
          {
            title: "Fallback",
            notes:
              "• Pesan error bila gagal autentikasi\n• Tombol kembali ke Login."
          }
        ],
        prototypeLinks: [
          {
            label: "Masuk sebagai Admin",
            target: "admin-catalog",
            transition: "DISSOLVE"
          },
          {
            label: "Masuk sebagai Owner",
            target: "owner-dashboard",
            transition: "DISSOLVE"
          },
          {
            label: "Kembali ke Login",
            target: "auth-login",
            transition: "INSTANT"
          }
        ]
      }
    ]
  },
  {
    name: "03 Admin (Kasir)",
    frames: [
      {
        key: "admin-catalog",
        name: "Catalog",
        sections: [
          {
            title: "App Bar",
            notes:
              "• Judul 'Katalog Barang'\n• Action keranjang badge\n• Profil via menu overflow."
          },
          {
            title: "Search & Filters",
            notes:
              "• TextField pencarian dengan ikon filter\n• Filter chips horizontal scroll."
          },
          {
            title: "Product List",
            notes:
              "• Thumbnail 64 px, nama, SKU, harga\n• Stepper +/- terhubung ke keranjang."
          },
          {
            title: "Cart Bar",
            notes:
              "• Sticky bottom: 'Sub Total' + total rupiah\n• Tombol 'Lanjut' → keranjang."
          }
        ],
        prototypeLinks: [
          {
            label: "Lanjut ke Keranjang",
            target: "admin-cart",
            transition: "DISSOLVE"
          },
          {
            label: "Buka Goods (Bottom Nav)",
            target: "admin-goods",
            transition: "INSTANT"
          },
          {
            label: "Buka Report (Bottom Nav)",
            target: "admin-report",
            transition: "INSTANT"
          },
          {
            label: "Buka Profil & Logout",
            target: "admin-profile-dialog",
            transition: "DISSOLVE"
          }
        ]
      },
      {
        key: "admin-cart",
        name: "Cart",
        sections: [
          {
            title: "App Bar",
            notes: "• Judul 'Keranjang'\n• Navigasi kembali standar."
          },
          {
            title: "Cart Items",
            notes:
              "• Kartu produk dengan qty stepper\n• Tampilkan harga satuan & subtotal."
          },
          {
            title: "Summary",
            notes:
              "• Diskon, biaya layanan toggle, catatan pesanan\n• Rekap subtotal, pajak bila ada."
          },
          {
            title: "Primary Action",
            notes:
              "• Tombol 'Lanjut Pembayaran'\n• Sticky bersama total besar."
          }
        ],
        prototypeLinks: [
          {
            label: "Lanjut Pembayaran",
            target: "admin-payment",
            transition: "DISSOLVE"
          },
          {
            label: "Kembali ke Catalog",
            target: "admin-catalog",
            transition: "INSTANT"
          }
        ]
      },
      {
        key: "admin-payment",
        name: "Payment",
        sections: [
          {
            title: "Transaction Summary",
            notes:
              "• Total tagihan besar\n• Breakdown subtotal, diskon, layanan."
          },
          {
            title: "Payment Method",
            notes:
              "• Radio list: Tunai, Transfer, QRIS\n• Bukti bayar opsional."
          },
          {
            title: "Cash Handling",
            notes:
              "• Input nominal diterima\n• Kembalian otomatis\n• Toggle cetak struk."
          },
          {
            title: "Confirmation",
            notes:
              "• Tombol 'Selesaikan Transaksi'\n• Dialog bila nominal kurang."
          }
        ],
        prototypeLinks: [
          {
            label: "Selesaikan Transaksi",
            target: "admin-receipt",
            transition: "DISSOLVE"
          },
          {
            label: "Batalkan ke Keranjang",
            target: "admin-cart",
            transition: "INSTANT"
          }
        ]
      },
      {
        key: "admin-receipt",
        name: "Receipt",
        sections: [
          {
            title: "Header",
            notes:
              "• Ikon sukses + 'Transaksi Berhasil'\n• Info waktu transaksi."
          },
          {
            title: "Receipt Body",
            notes:
              "• Nama outlet, daftar barang, total, metode bayar\n• Nomor struk / referensi."
          },
          {
            title: "Actions",
            notes:
              "• Tombol 'Bagikan', 'Cetak', 'Kembali ke Katalog'\n• Opsi simpan PDF."
          }
        ],
        prototypeLinks: [
          {
            label: "Kembali ke Catalog",
            target: "admin-catalog",
            transition: "DISSOLVE"
          }
        ]
      },
      {
        key: "admin-goods",
        name: "Goods",
        sections: [
          {
            title: "Header & Filters",
            notes:
              "• App bar 'Stok Barang'\n• Chips: Semua, Hampir Habis, Non Aktif."
          },
          {
            title: "Inventory List",
            notes:
              "• Item: nama, stok, badge status, progress bar\n• Menu detail stok."
          },
          {
            title: "Actions",
            notes:
              "• FAB '+ Barang Baru' (opsional)\n• Impor CSV opsional."
          }
        ],
        prototypeLinks: [
          {
            label: "Kembali ke Catalog",
            target: "admin-catalog",
            transition: "INSTANT"
          },
          {
            label: "Lihat Report",
            target: "admin-report",
            transition: "INSTANT"
          }
        ]
      },
      {
        key: "admin-report",
        name: "Report",
        sections: [
          {
            title: "Range Filters",
            notes:
              "• Chips: Hari ini, 7, 30 hari\n• Date picker kustom opsional."
          },
          {
            title: "KPI Cards",
            notes:
              "• Total Transaksi, Omzet, Rata-rata Nota\n• Gunakan card highlight."
          },
          {
            title: "Transactions List",
            notes:
              "• Tabel/list histori dengan search & filter metode\n• Tap item membuka detail."
          },
          {
            title: "Detail Drawer",
            notes:
              "• Slide-up sheet berisi rincian transaksi."
          }
        ],
        prototypeLinks: [
          {
            label: "Buka Detail Transaksi",
            target: "admin-payment",
            transition: "DISSOLVE"
          },
          {
            label: "Kembali ke Catalog",
            target: "admin-catalog",
            transition: "INSTANT"
          }
        ]
      },
      {
        key: "admin-profile-dialog",
        name: "Profile Dialog",
        sections: [
          {
            title: "Content",
            notes:
              "• Email pengguna & peran\n• Tombol 'Keluar' dan 'Batal'."
          }
        ],
        prototypeLinks: [
          {
            label: "Keluar ke Login",
            target: "auth-login",
            transition: "DISSOLVE"
          },
          {
            label: "Tutup dan kembali",
            target: "admin-catalog",
            transition: "INSTANT"
          }
        ]
      }
    ]
  },
  {
    name: "04 Super Admin (Owner)",
    frames: [
      {
        key: "owner-dashboard",
        name: "Dashboard",
        sections: [
          {
            title: "Greeting & Range",
            notes:
              "• Teks 'Halo, Owner!'\n• Chip rentang: Hari ini, 7, 30, 90."
          },
          {
            title: "Summary Cards",
            notes:
              "• Grid 2 kolom: Penjualan, Transaksi, Rata-rata, Produk.\n• State kosong bila tanpa data."
          },
          {
            title: "Charts",
            notes:
              "• Line chart pendapatan\n• Pie chart Top 5 Produk."
          },
          {
            title: "Today Snapshot",
            notes:
              "• Card khusus saat range Hari Ini\n• Tampilkan transaksi hari ini, pendapatan, refund."
          },
          {
            title: "Pending Alerts",
            notes:
              "• Card permintaan penyesuaian stok + CTA 'Lihat'."
          }
        ],
        prototypeLinks: [
          {
            label: "Buka Inventory",
            target: "owner-inventory",
            transition: "INSTANT"
          },
          {
            label: "Buka Report",
            target: "owner-report",
            transition: "INSTANT"
          },
          {
            label: "Lihat Adjust Request",
            target: "owner-adjust",
            transition: "INSTANT"
          },
          {
            label: "Buka Notifikasi",
            target: "owner-notification",
            transition: "DISSOLVE"
          }
        ]
      },
      {
        key: "owner-inventory",
        name: "Inventory",
        sections: [
          {
            title: "Filters",
            notes:
              "• Search, chips status (Masuk, Keluar, Penyesuaian)\n• Date range picker."
          },
          {
            title: "Timeline List",
            notes:
              "• Card berisi tanggal, user, ringkasan qty\n• Expand menampilkan detail item."
          },
          {
            title: "Actions",
            notes:
              "• FAB 'Tambah Penyesuaian'\n• Shortcut impor data."
          }
        ],
        prototypeLinks: [
          {
            label: "Ajukan Penyesuaian",
            target: "owner-adjust",
            transition: "DISSOLVE"
          },
          {
            label: "Ke Laporan",
            target: "owner-report",
            transition: "INSTANT"
          },
          {
            label: "Kelola Produk",
            target: "owner-product-list",
            transition: "DISSOLVE"
          }
        ]
      },
      {
        key: "owner-adjust",
        name: "Adjust Request",
        sections: [
          {
            title: "Tabs",
            notes:
              "• Segmented: Pending, Disetujui, Ditolak."
          },
          {
            title: "Request Card",
            notes:
              "• Info outlet, item list, catatan\n• Status badge dan timestamp."
          },
          {
            title: "Approval Actions",
            notes:
              "• Tombol 'Setujui', 'Tolak', 'Detail'\n• Dialog konfirmasi catatan."
          }
        ],
        prototypeLinks: [
          {
            label: "Buka Report Owner",
            target: "owner-report",
            transition: "INSTANT"
          },
          {
            label: "Kembali ke Dashboard",
            target: "owner-dashboard",
            transition: "INSTANT"
          }
        ]
      },
      {
        key: "owner-report",
        name: "Report",
        sections: [
          {
            title: "Filter Header",
            notes:
              "• Rentang tanggal, outlet, kategori dropdown\n• Preset mingguan/bulanan."
          },
          {
            title: "KPI Overview",
            notes:
              "• Kartu kinerja + perbandingan periode."
          },
          {
            title: "Charts & Tables",
            notes:
              "• Stacked bar / area chart\n• Tabel: Tanggal, Transaksi, Omzet, Laba, Metode."
          },
          {
            title: "Export Actions",
            notes:
              "• Tombol 'Download CSV' & 'Unduh PDF'\n• Info terakhir diekspor."
          }
        ],
        prototypeLinks: [
          {
            label: "Kelola Produk",
            target: "owner-product-list",
            transition: "DISSOLVE"
          },
          {
            label: "Kembali ke Dashboard",
            target: "owner-dashboard",
            transition: "INSTANT"
          }
        ]
      },
      {
        key: "owner-product-list",
        name: "Product List",
        sections: [
          {
            title: "Segment Control",
            notes:
              "• Toggle 'Barang' vs 'Jasa'."
          },
          {
            title: "Search & Filters",
            notes:
              "• Search bar, dropdown kategori, toggle stok rendah."
          },
          {
            title: "Product Grid",
            notes:
              "• Kartu 2 kolom: thumbnail, nama, SKU, stok, status."
          },
          {
            title: "Bulk Actions",
            notes:
              "• Bar muncul saat multi-select: Hapus, Arsip\n• FAB '+ Produk Baru'."
          }
        ],
        prototypeLinks: [
          {
            label: "Tambah Produk Baru",
            target: "owner-product-form",
            transition: "DISSOLVE"
          },
          {
            label: "Kelola Supplier",
            target: "owner-supplier",
            transition: "INSTANT"
          },
          {
            label: "Kelola Kategori",
            target: "owner-category",
            transition: "INSTANT"
          },
          {
            label: "Kembali ke Dashboard",
            target: "owner-dashboard",
            transition: "INSTANT"
          }
        ]
      },
      {
        key: "owner-product-form",
        name: "Product Form",
        sections: [
          {
            title: "Stepper Header",
            notes:
              "• Tahap: Detail Utama → Harga & Stok → Gambar."
          },
          {
            title: "Detail Fields",
            notes:
              "• Nama, SKU, kategori, satuan."
          },
          {
            title: "Pricing & Stock",
            notes:
              "• Harga jual/beli (rupiah formatter)\n• Stok awal, notif stok rendah, approval toggle."
          },
          {
            title: "Media",
            notes:
              "• Upload gambar, preview, hapus."
          },
          {
            title: "Action Bar",
            notes:
              "• Tombol 'Batal', 'Simpan'\n• Indikator validasi."
          }
        ],
        prototypeLinks: [
          {
            label: "Simpan & Kembali",
            target: "owner-product-list",
            transition: "DISSOLVE"
          }
        ]
      },
      {
        key: "owner-supplier",
        name: "Supplier",
        sections: [
          {
            title: "List",
            notes:
              "• Kartu: nama, kontak, tag kategori barang."
          },
          {
            title: "Actions",
            notes:
              "• Tombol 'Hubungi', 'Edit'\n• CTA '+ Supplier'."
          }
        ],
        prototypeLinks: [
          {
            label: "Kembali ke Produk",
            target: "owner-product-list",
            transition: "INSTANT"
          }
        ]
      },
      {
        key: "owner-category",
        name: "Category",
        sections: [
          {
            title: "List Layout",
            notes:
              "• Daftar reorderable dengan drag handle."
          },
          {
            title: "Item Details",
            notes:
              "• Nama kategori, jumlah produk, toggle aktif."
          },
          {
            title: "Modal",
            notes:
              "• Form tambah / edit kategori ringkas."
          }
        ],
        prototypeLinks: [
          {
            label: "Kembali ke Produk",
            target: "owner-product-list",
            transition: "INSTANT"
          }
        ]
      },
      {
        key: "owner-notification",
        name: "Notification",
        sections: [
          {
            title: "Header",
            notes:
              "• App bar dengan tombol back\n• Bottom nav disembunyikan."
          },
          {
            title: "List",
            notes:
              "• Item: ikon status, judul, deskripsi, waktu\n• Indikator unread (dot / bold)."
          },
          {
            title: "Filters & Bulk",
            notes:
              "• Chips: Semua, Prioritas, Belum Dibaca\n• Tombol 'Tandai sudah dibaca'."
          }
        ],
        prototypeLinks: [
          {
            label: "Kembali ke Dashboard",
            target: "owner-dashboard",
            transition: "DISSOLVE"
          }
        ]
      }
    ]
  },
  {
    name: "05 States & Overlays",
    frames: [
      {
        key: "states-loading",
        name: "Loading",
        sections: [
          {
            title: "Skeletons",
            notes:
              "• Placeholder card/list dengan shimmer."
          },
          {
            title: "Spinner",
            notes:
              "• Progress indicator + teks 'Memuat data...'."
          }
        ]
      },
      {
        key: "states-empty",
        name: "Empty",
        sections: [
          {
            title: "Visual",
            notes:
              "• Ilustrasi sederhana atau ikon outline."
          },
          {
            title: "Copy",
            notes:
              "• Judul positif, sub copy pendek, CTA."
          }
        ]
      },
      {
        key: "states-error",
        name: "Error",
        sections: [
          {
            title: "Snackbar",
            notes:
              "• Snackbar merah dengan aksi 'Coba lagi'."
          },
          {
            title: "Dialog",
            notes:
              "• Dialog error + CTA 'Hubungi Dukungan'."
          }
        ]
      },
      {
        key: "states-offline",
        name: "Offline",
        sections: [
          {
            title: "Banner",
            notes:
              "• Banner merah: 'Tidak ada akses internet'."
          },
          {
            title: "Recovery",
            notes:
              "• Tombol 'Coba lagi'\n• Informasi status jaringan."
          }
        ]
      }
    ]
  }
];

async function main() {
  await loadFonts();

  let lastCreatedPage = null;

  for (const pageData of pagesData) {
    let page = figma.root.children.find(
      (node) => node.type === "PAGE" && node.name === pageData.name
    );
    if (!page) {
      page = figma.createPage();
      page.name = pageData.name;
    }

    let offsetX = 0;
    const existingFrames = page.children.filter((node) => node.type === "FRAME");
    if (existingFrames.length > 0) {
      offsetX =
        existingFrames.reduce((max, node) => Math.max(max, node.x + node.width), 0) +
        HORIZONTAL_GAP;
    }

    for (const frameData of pageData.frames) {
      if (!frameData.key) {
        continue;
      }

      const alreadyExists = page.children.some(
        (node) => node.type === "FRAME" && node.name === frameData.name
      );
      if (alreadyExists) {
        const existingNode = page.children.find(
          (node) => node.type === "FRAME" && node.name === frameData.name
        );
        if (existingNode) {
          frameRegistry.set(frameData.key, existingNode);
        }
        continue;
      }

      const frame = createScreenFrame(frameData);
      frame.x = offsetX;
      frame.y = 0;
      page.appendChild(frame);
      frameRegistry.set(frameData.key, frame);
      offsetX += FRAME_WIDTH + HORIZONTAL_GAP;
    }

    lastCreatedPage = page;
  }

  attachPrototypeLinks();

  if (lastCreatedPage) {
    figma.currentPage = lastCreatedPage;
  }
}

function createScreenFrame(frameData) {
  const frame = figma.createFrame();
  frame.name = frameData.name;
  frame.resizeWithoutConstraints(FRAME_WIDTH, FRAME_HEIGHT);
  frame.layoutMode = "VERTICAL";
  frame.counterAxisSizingMode = "FIXED";
  frame.primaryAxisSizingMode = "AUTO";
  frame.paddingLeft = 16;
  frame.paddingRight = 16;
  frame.paddingTop = 24;
  frame.paddingBottom = 24;
  frame.itemSpacing = 16;
  frame.cornerRadius = 24;
  frame.strokes = [];
  frame.fills = [
    {
      type: "SOLID",
      color: { r: 1, g: 1, b: 1 }
    }
  ];
  frame.effects = [
    {
      type: "DROP_SHADOW",
      color: { r: 0, g: 0, b: 0, a: 0.08 },
      offset: { x: 0, y: 12 },
      radius: 32,
      spread: -24,
      visible: true,
      blendMode: "NORMAL"
    }
  ];
  frame.layoutGrids = [
    {
      pattern: "COLUMNS",
      sectionSize: 73,
      gutterSize: 12,
      alignment: "MIN",
      count: 4,
      offset: 16,
      visible: true
    }
  ];

  const title = figma.createText();
  title.name = "Screen Title";
  title.fontName = activeFont;
  title.fontSize = 20;
  title.lineHeight = { value: 28, unit: "PIXELS" };
  title.characters = frameData.name;
  title.fills = [{ type: "SOLID", color: COLORS.textStrong }];
  title.textAutoResize = "WIDTH_AND_HEIGHT";
  title.layoutAlign = "STRETCH";
  frame.appendChild(title);

  const sectionList = frameData.sections ? frameData.sections : [];
  for (const section of sectionList) {
    const sectionFrame = createSectionFrame(section);
    frame.appendChild(sectionFrame);
  }

  if (frameData.prototypeLinks && frameData.prototypeLinks.length > 0) {
    const prototypePanel = createPrototypePanel(frameData.prototypeLinks);
    frame.appendChild(prototypePanel);
  }

  return frame;
}

function createSectionFrame(section) {
  const container = figma.createFrame();
  container.name = section.title;
  container.layoutMode = "VERTICAL";
  container.counterAxisSizingMode = "AUTO";
  container.primaryAxisSizingMode = "AUTO";
  container.paddingLeft = 16;
  container.paddingRight = 16;
  container.paddingTop = 16;
  container.paddingBottom = 16;
  container.itemSpacing = 8;
  container.cornerRadius = 12;
  container.strokes = [{ type: "SOLID", color: COLORS.sectionBorder }];
  container.strokeWeight = 1;
  container.fills = [{ type: "SOLID", color: COLORS.sectionFill }];
  container.layoutAlign = "STRETCH";

  const title = figma.createText();
  title.name = "Section Title";
  title.fontName = activeFont;
  title.fontSize = 16;
  title.lineHeight = { value: 24, unit: "PIXELS" };
  title.characters = section.title;
  title.fills = [{ type: "SOLID", color: COLORS.textStrong }];
  title.textAutoResize = "WIDTH_AND_HEIGHT";
  title.layoutAlign = "STRETCH";
  container.appendChild(title);

  if (section.notes) {
    const notes = figma.createText();
    notes.name = "Notes";
    notes.fontName = activeFont;
    notes.fontSize = 12;
    notes.lineHeight = { value: 18, unit: "PIXELS" };
    notes.characters = section.notes;
    notes.fills = [{ type: "SOLID", color: COLORS.textMuted }];
    notes.textAutoResize = "WIDTH_AND_HEIGHT";
    notes.layoutAlign = "STRETCH";
    container.appendChild(notes);
  }

  return container;
}

function createPrototypePanel(links) {
  const container = figma.createFrame();
  container.name = "Prototype Actions";
  container.layoutMode = "VERTICAL";
  container.counterAxisSizingMode = "AUTO";
  container.primaryAxisSizingMode = "AUTO";
  container.paddingLeft = 12;
  container.paddingRight = 12;
  container.paddingTop = 12;
  container.paddingBottom = 12;
  container.itemSpacing = 8;
  container.cornerRadius = 12;
  container.strokes = [{ type: "SOLID", color: COLORS.sectionBorder }];
  container.strokeWeight = 1;
  container.fills = [{ type: "SOLID", color: { r: 0.97, g: 0.98, b: 1 } }];
  container.layoutAlign = "STRETCH";

  const title = figma.createText();
  title.name = "Prototype Title";
  title.fontName = activeFont;
  title.fontSize = 14;
  title.lineHeight = { value: 20, unit: "PIXELS" };
  title.characters = "Prototype Flow";
  title.fills = [{ type: "SOLID", color: COLORS.textStrong }];
  title.textAutoResize = "WIDTH_AND_HEIGHT";
  title.layoutAlign = "STRETCH";
  container.appendChild(title);

  for (const link of links) {
    const button = createPrototypeButton(link.label);
    container.appendChild(button);
    pendingPrototypeLinks.push({
      node: button,
      targetKey: link.target,
      transition: link.transition || "DISSOLVE"
    });
  }

  return container;
}

function createPrototypeButton(label) {
  const button = figma.createFrame();
  button.name = `Action · ${label}`;
  button.layoutMode = "HORIZONTAL";
  button.counterAxisSizingMode = "AUTO";
  button.primaryAxisSizingMode = "AUTO";
  button.counterAxisAlignItems = "CENTER";
  button.primaryAxisAlignItems = "CENTER";
  button.paddingLeft = 16;
  button.paddingRight = 16;
  button.paddingTop = 12;
  button.paddingBottom = 12;
  button.itemSpacing = 8;
  button.cornerRadius = 12;
  button.fills = [{ type: "SOLID", color: COLORS.primary }];
  button.strokes = [];
  button.effects = [
    {
      type: "DROP_SHADOW",
      color: { r: 0, g: 0, b: 0, a: 0.12 },
      offset: { x: 0, y: 6 },
      radius: 12,
      spread: -6,
      visible: true,
      blendMode: "NORMAL"
    }
  ];
  button.layoutAlign = "STRETCH";

  const text = figma.createText();
  text.name = "Label";
  text.fontName = activeFont;
  text.fontSize = 14;
  text.lineHeight = { value: 20, unit: "PIXELS" };
  text.characters = label;
  text.textAlignHorizontal = "CENTER";
  text.textAlignVertical = "CENTER";
  text.fills = [{ type: "SOLID", color: COLORS.onPrimary }];
  text.textAutoResize = "WIDTH_AND_HEIGHT";
  text.layoutAlign = "CENTER";
  button.appendChild(text);

  return button;
}

function attachPrototypeLinks() {
  for (const link of pendingPrototypeLinks) {
    const targetFrame = frameRegistry.get(link.targetKey);
    if (!targetFrame) {
      console.warn(`Target frame not found for key: ${link.targetKey}`);
      continue;
    }

    const action = {
      type: "NODE",
      destinationId: targetFrame.id,
      navigation: "NAVIGATE",
      transition: { type: "INSTANT" }
    };

    if (link.transition && link.transition !== "INSTANT") {
      action.transition = {
        type: "DISSOLVE",
        easing: { type: "EASE_IN_OUT" },
        duration: 0.32
      };
    }

    link.node.reactions = [
      {
        trigger: { type: "ON_CLICK" },
        action
      }
    ];
  }
}

main()
  .then(() => {
    figma.closePlugin("POSHMA scaffold & prototype links generated.");
  })
  .catch((error) => {
    console.error(error);
    const message =
      error && error.message ? error.message : (error ? String(error) : "Tidak diketahui");
    figma.closePlugin("Terjadi kesalahan: " + message);
  });

async function loadFonts() {
  try {
    await figma.loadFontAsync(FONT_PRIMARY);
    activeFont = FONT_PRIMARY;
  } catch (error) {
    console.warn("Inter font unavailable, fallback to Roboto.", error);
    activeFont = FONT_FALLBACK;
    await figma.loadFontAsync(activeFont);
  }
}
