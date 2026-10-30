"""Make a narration clip in the chosen voice plus the say-file that tells the mod to play it.
Usage: narrate.py "text" -> prints the two staged paths (wav, say file) and the clip name."""
import sys, wave, time, glob
from piper import PiperVoice
from piper.config import SynthesisConfig
text = sys.argv[1]
stamp = time.strftime("%H%M%S") + str(int(time.time() * 1000) % 1000)
name = f"clip{stamp}.wav"
wav_path = f"/mnt/user-data/outputs/voice/{name}"
say_path = f"/mnt/user-data/outputs/bridge/say-{stamp}.txt"
model = glob.glob("/tmp/claude-0/voices/voice-en-us-lessac-medium/*.onnx")[0]
v = PiperVoice.load(model)
with wave.open(wav_path, "wb") as w:
    v.synthesize_wav(text, w, syn_config=SynthesisConfig(length_scale=0.92))
open(say_path, "w").write(f"#wav {name}\n")
print(wav_path); print(say_path); print(name)
