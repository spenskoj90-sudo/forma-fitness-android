SYNTHETIC TEST FIXTURE ONLY — NOT AN EXERCISE DEMONSTRATION.

v1.mp4 is a 32×32 blue frame, 10 fps, one second, silent H.264 baseline.
Generated locally using FFmpeg:
ffmpeg -f lavfi -i color=c=blue:s=32x32:r=10 -t 1 -an -c:v libx264 -profile:v baseline -pix_fmt yuv420p -movflags +faststart technique/squat/v1.mp4
SHA-256: b0392fdf60571ad3b97decb08222ab9a5c3e436b13bfdd5e2477e07ef50b51fd

All manifest attribution/review/captions are explicitly synthetic test inputs.
No real instructor, license review or correctness of movement is asserted.
This source set is packaged only in the instrumentation test APK, never in FORMA's user APK.
