param([Parameter(Mandatory=$true)][string]$NdkHome,[Parameter(Mandatory=$true)][string]$Output)
$ErrorActionPreference='Stop'
$nativeProject=Join-Path (Split-Path $PSScriptRoot -Parent) 'math-rust'
$nativeCargo=if ($env:CARGO_HOME) { Join-Path $env:CARGO_HOME 'bin/cargo.exe' } else { 'cargo.exe' }
$llvmBin=Join-Path $NdkHome 'toolchains/llvm/prebuilt/windows-x86_64/bin'
if (-not (Test-Path -LiteralPath $llvmBin -PathType Container)) { throw 'Install the configured Android NDK first' }
$targets=@(
    @('aarch64-linux-android','arm64-v8a','aarch64-linux-android26'),
    @('armv7-linux-androideabi','armeabi-v7a','armv7a-linux-androideabi26'),
    @('x86_64-linux-android','x86_64','x86_64-linux-android26'),
    @('i686-linux-android','x86','i686-linux-android26')
)
foreach ($target in $targets) {
    $linkerKey='CARGO_TARGET_'+$target[0].Replace('-','_').ToUpperInvariant()+'_LINKER'
    [Environment]::SetEnvironmentVariable($linkerKey,(Join-Path $llvmBin ($target[2]+'-clang.cmd')),'Process')
    & $nativeCargo build --manifest-path (Join-Path $nativeProject 'Cargo.toml') --target $target[0] --release --locked
    if ($LASTEXITCODE -ne 0) { throw "Rust build failed: $($target[0])" }
    $destination=Join-Path $Output $target[1]
    [void][IO.Directory]::CreateDirectory($destination)
    Copy-Item -LiteralPath (Join-Path $nativeProject "target/$($target[0])/release/libshortcuts_math.so") -Destination (Join-Path $destination 'libshortcuts_math.so') -Force
}
