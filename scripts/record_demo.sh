#!/bin/sh
# Re-creates docs screenshots + an automated screen recording on a virtual display (Linux, needs xvfb + ffmpeg + JDK with AWT).
# Usage: scripts/record_demo.sh  (run build_without_maven.sh first)   Authors: YOUR_NAME_1 / YOUR_ID_1 , YOUR_NAME_2 / YOUR_ID_2
cd "$(dirname "$0")/.."
mkdir -p screenshots docs/test-results
xvfb-run -a -s "-screen 0 1920x1080x24" sh -c '
  ffmpeg -loglevel error -y -f x11grab -video_size 1920x1080 -framerate 8 -i "$DISPLAY" -c:v libx264 -preset veryfast -crf 28 -pix_fmt yuv420p docs/demo_automated_screen_recording.mp4 &
  FF=$!
  sleep 2
  java -Dpeerconnect.accounts=config/accounts.txt -cp tests/target/peerconnect-tests.jar com.peerconnect.tests.GuiScenario screenshots "${SLOW:-1.0}"
  RC=$?
  sleep 1; kill -INT $FF; wait $FF; exit $RC'
cp screenshots/gui-scenario-results.txt docs/test-results/gui-scenario-results.txt
