#!/bin/sh
curl -fsLO https://raw.githubusercontent.com/scijava/scijava-scripts/master/ci-setup-github-actions.sh
sh ci-setup-github-actions.sh

# NB: Needed to avoid CI test failure: java.lang.UnsatisfiedLinkError: /home/runner/.javacpp/cache/ffmpeg-4.2.1-1.5.2-linux-x86_64.jar/org/bytedeco/ffmpeg/linux-x86_64/libjniavdevice.so: libxcb-shape.so.0: cannot open shared object file: No such file or directory
sudo apt-get -y install libxcb-shape0
