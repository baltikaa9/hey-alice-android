package io.github.serifdrms.heyclaude.shizuku;

interface IAssistKeyService {

    // Transaction code Shizuku uses to tear the service down.
    void destroy() = 16777114;

    // Sends KEYCODE_ASSIST, the same event as holding the side key.
    boolean pressAssistKey() = 1;
}
