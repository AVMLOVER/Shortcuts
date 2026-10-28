#!/usr/bin/env bash
set -euo pipefail
ndk="$1"
out="$2"
project="$(cd "$(dirname "$0")/../math-rust" && pwd)"
host=linux-x86_64
if [[ "$(uname)" == Darwin ]]; then host=darwin-x86_64; fi
for spec in 'aarch64-linux-android arm64-v8a aarch64-linux-android26' 'armv7-linux-androideabi armeabi-v7a armv7a-linux-androideabi26' 'x86_64-linux-android x86_64 x86_64-linux-android26' 'i686-linux-android x86 i686-linux-android26'; do
  read -r target abi triple <<< "$spec"
  key="CARGO_TARGET_$(printf '%s' "$target" | tr '[:lower:]-' '[:upper:]_')_LINKER"
  export "$key=$ndk/toolchains/llvm/prebuilt/$host/bin/$triple-clang"
  cargo build --manifest-path "$project/Cargo.toml" --target "$target" --release --locked
  mkdir -p "$out/$abi"
  cp "$project/target/$target/release/libshortcuts_math.so" "$out/$abi/libshortcuts_math.so"
done
