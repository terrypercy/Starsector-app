// Claude Bridge voice: speaks the text in CLAUDE_SAY with a Windows voice.
// Text arrives as data in an environment variable and is never executed.
// Special text "__voices__" writes the installed voice list to voices.txt
// and plays a short sample of each one.
var sh = new ActiveXObject("WScript.Shell");
var env = sh.Environment("PROCESS");
var text = env("CLAUDE_SAY");
var v = new ActiveXObject("SAPI.SpVoice");
var rate = parseInt(env("CLAUDE_SAY_RATE"), 10);
if (!isNaN(rate)) v.Rate = rate;
var voices = v.GetVoices();
var wav = env("CLAUDE_WAV");

if (wav) {
    // Play a narration clip made by Claude, then delete it.
    var fs2 = new ActiveXObject("Scripting.FileSystemObject");
    if (fs2.FileExists(wav)) {
        var st = new ActiveXObject("SAPI.SpFileStream");
        st.Open(wav, 0, false);
        v.SpeakStream(st);
        st.Close();
        try { fs2.DeleteFile(wav); } catch (e) {}
    }
} else if (text == "__voices__") {
    var fso = new ActiveXObject("Scripting.FileSystemObject");
    var f = fso.CreateTextFile("..\\saves\\common\\claude_bridge\\voices.txt", true);
    for (var i = 0; i < voices.Count; i++) {
        f.WriteLine(i + ": " + voices.Item(i).GetDescription());
    }
    f.Close();
    for (var j = 0; j < voices.Count; j++) {
        v.Voice = voices.Item(j);
        v.Speak("Voice " + (j + 1) + ". This is " + voices.Item(j).GetDescription() + ". Ready to fly to Derinkuyu.");
    }
} else if (text) {
    var name = env("CLAUDE_SAY_VOICE");
    if (name) {
        for (var k = 0; k < voices.Count; k++) {
            if (voices.Item(k).GetDescription().toLowerCase().indexOf(name.toLowerCase()) >= 0) {
                v.Voice = voices.Item(k);
                break;
            }
        }
    }
    v.Speak(text);
}
