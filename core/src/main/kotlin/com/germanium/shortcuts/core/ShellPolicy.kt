package com.germanium.shortcuts.core

data class ShellAssessment(val denied: Boolean, val needsConfirmation: Boolean, val deleting: Boolean)
object ShellPolicy {
    fun quote(text: String): String { require(!text.contains('\u0000')) { throw Problem("err_invalid_data") }; return "'" + text.replace("'", "'\\''") + "'" }
    fun assess(command: String): ShellAssessment {
        require(command.isNotBlank() && command.length <= 32768 && !command.contains('\u0000')) { throw Problem("err_invalid_data") }
        val normalized = command.replace("\\\n", "").replace("\\", "").replace("'", "").replace("\"", "")
        // Reject root-wide removal even when flags or simple command wrappers are rearranged.
        val deletion = Regex("(?:^|[\\s;&|()])(?:[a-zA-Z0-9_/.-]*/)?(?:rm|rmdir|shred|wipe|dd|mkfs(?:\\.[a-z0-9]+)?)(?:[\\s;]|$)").containsMatchIn(normalized)
        val rootRemoval = Regex("(?:^|[\\s;&|()])(?:[a-zA-Z0-9_/.-]*/)?rm\\s+[^;\n]*?(?:^|\\s)/(?:\\*|\\.?(?:\\s|$)|\\s|$)").containsMatchIn(normalized)
        val rawDisk = Regex("(?:^|\\s)(?:of=)?/dev/(?:block|sd[a-z]|mmcblk|nvme)").containsMatchIn(normalized) && deletion
        val readOnly = Regex("\\s*(?:pwd|id|whoami|date|uname|uptime|ls|cat|head|tail|wc|stat|df|du|getprop|printf|echo)(?:\\s+[^;&|<>`$\n]*)?\\s*").matches(command)
        return ShellAssessment(rootRemoval || rawDisk, !readOnly || deletion, deletion)
    }
}
