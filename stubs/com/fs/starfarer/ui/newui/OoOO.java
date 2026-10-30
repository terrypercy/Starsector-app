// Compile-time stand-in for the game's obfuscated option panel (Starsector 0.98a-RC8).
// Only the members the mod calls are declared; signatures match the real class.
// "nwx" is renamed to the real method name "new" in the compiled mod class.
package com.fs.starfarer.ui.newui;
public class OoOO {
    public java.util.Map getButtonToItemMap() { return null; }
    public O0 getDelegate() { return null; }
    public interface O0 { void o00000(o item); }
    public static class o {
        public java.lang.String ø00000;   // confirmation popup text, if any
        public com.fs.starfarer.api.campaign.StoryPointActionDelegate Ò00000;   // story point cost, if any
        public java.lang.String o00000() { return null; }
        public java.lang.Object nwx() { return null; }
    }
}
