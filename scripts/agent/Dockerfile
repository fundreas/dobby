# syntax=docker/dockerfile:1

# The agent image: JDK 21 + the Android SDK + NDK/CMake + node + python + git +
# Claude Code, run by scripts/run-agent. Nothing of the project is baked in —
# the working copy is bind-mounted at run time, so the image only changes when
# the toolchain does.
#
# Build context is scripts/agent/ itself (see run-agent), so COPY paths below
# are relative to that directory.
#
# Sizing: the NDK alone is ~2.5 GB. Build with --build-arg ANDROID_NDK= (empty)
# to leave it out while the project is still pure-JVM; the llama.cpp JNI work
# (dobby-plan.md §4) needs it.

FROM eclipse-temurin:21-jdk-noble

ARG USER_NAME=agent
ARG USER_UID=1000
ARG USER_GID=1000

# Pinned deliberately: every version below was checked against Google's live
# repository manifest (dl.google.com/android/repository/repository2-3.xml) and
# nodejs.org/dist. Bump them here, not by hand inside a container.
ARG NODE_VERSION=22.23.2
ARG CMDLINE_TOOLS_ZIP=commandlinetools-linux-16111833_latest.zip
ARG ANDROID_PLATFORM=36
ARG ANDROID_BUILD_TOOLS=36.0.0
ARG ANDROID_NDK=28.2.13676358
ARG ANDROID_CMAKE=3.31.6

ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk

# python + the small tools Claude reaches for, plus unzip (sdkmanager) and
# usbutils (lsusb, for working out why adb cannot see the phone). No
# openssh-client and no gh on purpose: with no way to authenticate against
# GitHub, `git push` cannot work even if the hook were bypassed.
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      python3 python3-pip python3-venv \
      git jq ripgrep curl ca-certificates unzip zip xz-utils less procps tzdata file usbutils \
 && rm -rf /var/lib/apt/lists/*

# node is not needed by Claude Code itself (the native installer ships its own
# runtime) but npx is how most MCP servers start, so the ones configured in the
# shared ~/.claude keep working inside the container.
RUN curl -fsSL "https://nodejs.org/dist/v${NODE_VERSION}/node-v${NODE_VERSION}-linux-x64.tar.xz" \
      | tar -xJ -C /usr/local --strip-components=1 \
 && node --version && npm --version

# Recreate the container user with the caller's UID/GID so files written into
# the mounted project come out owned by you, not root. Ubuntu noble ships an
# `ubuntu` user on 1000 that would collide, so it goes first.
RUN userdel -r ubuntu 2>/dev/null || true \
 && (getent group "${USER_GID}" >/dev/null || groupadd --gid "${USER_GID}" "${USER_NAME}") \
 && useradd --uid "${USER_UID}" --gid "${USER_GID}" --create-home --shell /bin/bash "${USER_NAME}" \
 && mkdir -p "${ANDROID_HOME}" \
 && chown -R "${USER_UID}:${USER_GID}" "${ANDROID_HOME}"

# Everything from here runs as the agent: --dangerously-skip-permissions
# refuses to run as root, and an SDK owned by the user means `sdkmanager` can
# add a package mid-session (it lands in the container layer and is gone on
# exit — make it permanent by bumping the ARGs above).
USER ${USER_NAME}
ENV HOME=/home/${USER_NAME}

RUN curl -fsSL -o /tmp/cmdline-tools.zip \
      "https://dl.google.com/android/repository/${CMDLINE_TOOLS_ZIP}" \
 && unzip -q /tmp/cmdline-tools.zip -d /tmp/cmdline-tools \
 && mkdir -p "${ANDROID_HOME}/cmdline-tools" \
 && mv /tmp/cmdline-tools/cmdline-tools "${ANDROID_HOME}/cmdline-tools/latest" \
 && rm -rf /tmp/cmdline-tools.zip /tmp/cmdline-tools

ENV PATH=${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${PATH}

# Licences first, then the packages. No emulator and no system images: the
# target is a physical OnePlus Nord CE on arm64, and an arm64 emulator on an
# x86 host is not worth the gigabytes.
RUN yes 2>/dev/null | sdkmanager --licenses > /dev/null \
 && sdkmanager --install \
      "platform-tools" \
      "platforms;android-${ANDROID_PLATFORM}" \
      "build-tools;${ANDROID_BUILD_TOOLS}" \
      ${ANDROID_NDK:+"ndk;${ANDROID_NDK}"} \
      ${ANDROID_CMAKE:+"cmake;${ANDROID_CMAKE}"} \
 && adb version

ENV ANDROID_NDK_HOME=${ANDROID_HOME}/ndk/${ANDROID_NDK} \
    ANDROID_NDK_ROOT=${ANDROID_HOME}/ndk/${ANDROID_NDK}

# Created here so the named volumes run-agent mounts over them inherit the
# user's ownership instead of coming up root-owned. .android holds the adb
# key, so the phone's "always allow this computer" survives a container exit.
RUN mkdir -p ${HOME}/.gradle ${HOME}/.android ${HOME}/.m2
ENV GRADLE_USER_HOME=${HOME}/.gradle \
    ANDROID_USER_HOME=${HOME}/.android

# Claude Code, installed as the user via the native installer
# (~/.local/bin/claude).
ENV PATH=${HOME}/.local/bin:${PATH} \
    DISABLE_AUTOUPDATER=1 \
    CLAUDE_CODE_DISABLE_MOUSE_CLICKS=1
RUN curl -fsSL https://claude.ai/install.sh | bash \
 && claude --version

# The hook and its settings live outside the mounted project, root-owned (COPY
# ignores USER), so the agent cannot edit its own guard rails. Last, so an edit
# here only rebuilds this layer — run-agent rebuilds on its own when these files
# are newer than the image.
COPY --chmod=0755 guard.py /opt/agent/guard.py
COPY --chmod=0644 settings.json /opt/agent/settings.json

CMD ["claude"]
