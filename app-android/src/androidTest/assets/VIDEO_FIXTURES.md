# Synthetic MP4 fixtures

These are generated color frames with an artificial 440 Hz sine tone. No X media,
accounts, credentials, third-party images, voices or copyrighted recordings are used.
Base64 text is decoded only by instrumentation; it is not packaged in the client APK.

Generated with FFmpeg 7.1.5:

```sh
ffmpeg -nostdin -v error -f lavfi -i color=c=0x22aa44:s=64x64:r=12 -f lavfi -i sine=frequency=440:sample_rate=22050 -t 1 -c:v libx264 -profile:v baseline -level 1.0 -pix_fmt yuv420p -c:a aac -b:a 24k -ar 22050 -ac 1 -movflags +faststart standard.mp4
ffmpeg -nostdin -v error -i standard.mp4 -c copy -movflags frag_keyframe+empty_moov fragmented.mp4
```

- standard.mp4: 5783 bytes, SHA256 `c87f572d025de1d8a116cd588806c702de337adf17262ce4da7e92a8cd03133b`
- fragmented.mp4: 5943 bytes, SHA256 `384f9b371cc6e7762ab91c3fd11ef0a5d0a695be9440380cf2e710d65778c4c5`

The fragmented sample must be rejected, not renamed or shared as a complete video.
