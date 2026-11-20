# R8 hanya dipakai untuk men-dex (termasuk desugaring record milik NewPipeExtractor v0.27+),
# BUKAN untuk memperkecil/mengacak kode. Perilaku app sama seperti tanpa minify.
# Tanpa ini, dexing per-jar bawaan AGP gagal: "global synthetic for 'Record desugaring'".
-dontshrink
-dontoptimize
-dontobfuscate
