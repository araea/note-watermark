package com.jy.notewatermark;

/** The one setting the module owns: what the footer of a shared picture should become. */
final class FooterConfig {
    final String watermarkText;
    final boolean keepBlankSpace;

    FooterConfig(String watermarkText, boolean keepBlankSpace) {
        this.watermarkText = watermarkText;
        this.keepBlankSpace = keepBlankSpace;
    }

    /** A line of the user's own replaces the brand. */
    boolean custom() { return !watermarkText.isEmpty(); }

    /** No footer at all, and no room kept for it. */
    boolean hidden() { return watermarkText.isEmpty() && !keepBlankSpace; }
}
