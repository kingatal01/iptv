# Enregistre la voix off de la vidéo : un fichier WAV par séquence, à vitesse naturelle.
# À lancer avec Windows PowerShell 5.1 (System.Speech n'existe pas dans PowerShell 7).
# Le fichier doit être en UTF-8 avec BOM, sinon PowerShell 5.1 lit mal les accents.
param([string]$OutDir)
Add-Type -AssemblyName System.Speech
New-Item -ItemType Directory -Force $OutDir | Out-Null

$lines = @(
    "IPTV Player. Toutes vos chaînes, vérifiées et organisées.",
    "Démarrez en quelques secondes, avec une adresse, un fichier, ou la liste par défaut.",
    "Seules les chaînes qui répondent entrent dans votre bibliothèque.",
    "La même bibliothèque sur téléphone, tablette et Android TV.",
    "Filtrez par catégorie, par pays ou par langue.",
    "Un lecteur complet : qualité, pistes audio et plein écran.",
    "Plus de mille cinq cents chaînes sont livrées avec l'application.",
    "La recherche automatique trouve de nouvelles chaînes, par langue et par mots-clés.",
    "Vous choisissez les sources à fouiller.",
    "Chaque flux est testé, et rien n'entre sans votre accord.",
    "Exportez vos chaînes par catégories, dans votre propre format.",
    "IPTV Player. Sur Android, Android TV et Windows."
)

$format = New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo 44100, ([System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen), ([System.Speech.AudioFormat.AudioChannel]::Mono)
for ($i = 0; $i -lt $lines.Count; $i++) {
    $file = Join-Path $OutDir ("{0:d2}.wav" -f $i)
    $synth = New-Object System.Speech.Synthesis.SpeechSynthesizer
    $synth.SelectVoice("Microsoft Hortense Desktop")
    $synth.Rate = 1
    $synth.SetOutputToWaveFile($file, $format)
    $synth.Speak($lines[$i])
    $synth.Dispose()
    $seconds = ((Get-Item $file).Length - 44) / (44100 * 2)
    "{0:d2} {1:n2}s" -f $i, $seconds
}
