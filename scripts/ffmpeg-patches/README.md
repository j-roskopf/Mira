# Local FFmpeg fixes

`avfoundation-audio-queue.patch` applies to FFmpeg 7.1.1 on macOS. Upstream's
AVFoundation audio callback retains only its latest sample buffer, releasing an
unread buffer when the next callback arrives. This drops microphone samples when
the demuxer or video pipeline cannot read quickly enough. Enlarging the FFmpeg
input packet queue alone did not solve this on the MacBook Pro camera/microphone.

The patch retains pending audio buffers in arrival order, under the existing frame
lock. The queue is bounded at 256 pending buffers; overflow fails capture instead
of silently writing discontinuous audio. Retained buffers are released on capture
shutdown. Video still uses the existing latest-frame behavior.

Build with `scripts/build-ffmpeg.sh macos`. The patch applies idempotently and the
build fails if neither the forward nor reverse patch matches. Release cache keys
include patch contents.

Hardware validation on 2026-10-31 used a four-second 1920x1080, 30 fps recording,
H.264 plus AAC, and simultaneous MJPEG preview. Before the patch, AAC packet timing
contained 46 jumps of about 10.67 ms (about 0.49 seconds of missing samples). After
the patch, all full AAC packets advanced by 1024/44100 seconds; the total timing
rounding difference was below 0.01 ms. This measurement exercises real microphone
callbacks, which synthetic lavfi tests do not.
