// Compile-time stand-in for the game's combat state (implements CombatUIAPI in the real game).
package com.fs.starfarer.combat;
public class CombatState {
    public void setShowDeploymentDialogOnStart(boolean b) {}
    public void setAutopilot(boolean b) {}
    public boolean isShowingDeploymentDialog() { return false; }
}
