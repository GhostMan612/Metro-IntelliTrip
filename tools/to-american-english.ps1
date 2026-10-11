# Converts British English spellings to American English across the repo.
#
# Applied with a fixed map rather than a general rule so that words which merely
# end in -ise (promise, precise, exercise, noise, raise) are never touched, and so
# that every change is auditable in this one file.

$ErrorActionPreference = 'Stop'

# Ordered longest-first within each group so a longer form is consumed before a
# shorter prefix of it can match.
$replacements = [ordered]@{
    # --- nouns / adjectives -------------------------------------------------
    'behaviour'      = 'behavior'
    'behaviours'     = 'behaviors'
    'centre'         = 'center'
    'centres'        = 'centers'
    'metres'         = 'meters'
    'metre'          = 'meter'
    'colour'         = 'color'
    'colours'        = 'colors'
    'grey'           = 'gray'
    'licence'        = 'license'
    'defence'        = 'defense'
    'offence'        = 'offense'
    'artefact'       = 'artifact'
    'artefacts'      = 'artifacts'
    'catalogue'      = 'catalog'
    'dialogue'       = 'dialog'
    'favourite'      = 'favorite'
    'neighbour'      = 'neighbor'
    'traveller'      = 'traveler'
    'travelling'     = 'traveling'
    'signalling'     = 'signaling'
    'fuelled'        = 'fueled'
    'labelled'       = 'labeled'
    'modelled'       = 'modeled'
    'totalled'       = 'totaled'
    'totalling'      = 'totaling'
    'cancelled'      = 'canceled'
    'cancelling'     = 'canceling'
    'judgement'      = 'judgment'
    'acknowledgement'= 'acknowledgment'
    'ageing'         = 'aging'
    'aluminium'      = 'aluminum'
    'enquiry'        = 'inquiry'
    'storey'         = 'story'
    'tyre'           = 'tire'
    'plough'         = 'plow'
    'sceptical'      = 'skeptical'
    'marvellous'     = 'marvelous'
    'jewellery'      = 'jewelry'
    'cheque'         = 'check'

    # --- verbs --------------------------------------------------------------
    'materialising'  = 'materializing'
    'materialise'    = 'materialize'
    'normalising'    = 'normalizing'
    'normalise'      = 'normalize'
    'initialising'   = 'initializing'
    'initialise'     = 'initialize'
    'serialising'    = 'serializing'
    'serialise'      = 'serialize'
    'summarising'    = 'summarizing'
    'summarise'      = 'summarize'
    'optimising'     = 'optimizing'
    'optimise'       = 'optimize'
    'recognising'    = 'recognizing'
    'recognise'      = 'recognize'
    'authorising'    = 'authorizing'
    'authorise'      = 'authorize'
    'categorising'   = 'categorizing'
    'categorise'     = 'categorize'
    'customising'    = 'customizing'
    'customise'      = 'customize'
    'standardising'  = 'standardizing'
    'standardise'    = 'standardize'
    'specialising'   = 'specializing'
    'specialise'     = 'specialize'
    'organising'     = 'organizing'
    'organise'       = 'organize'
    'maximising'     = 'maximizing'
    'maximise'       = 'maximize'
    'minimising'     = 'minimizing'
    'minimise'       = 'minimize'
    'prioritising'   = 'prioritizing'
    'prioritise'     = 'prioritize'
    'randomising'    = 'randomizing'
    'randomise'      = 'randomize'
    'synchronising'  = 'synchronizing'
    'synchronise'    = 'synchronize'
    'utilising'      = 'utilizing'
    'utilise'        = 'utilize'
    'visualising'    = 'visualizing'
    'visualise'      = 'visualize'
    'emphasising'    = 'emphasizing'
    'emphasise'      = 'emphasize'
    'realising'      = 'realizing'
    'realise'        = 'realize'
    'centring'       = 'centering'
    'centred'        = 'centered'
    'centreing'      = 'centering'
    're-centres'     = 're-centers'
    'recentre'       = 'recenter'
    'recentreing'    = 'recentering'
}

# Files to sweep: source, docs and CI. Build output, caches, git internals and the
# protected prototype are excluded.
$roots = Get-ChildItem -Recurse -File -Include `
    *.kt, *.kts, *.md, *.yml, *.yaml, *.xml, *.pro, *.properties, *.json `
    -ErrorAction SilentlyContinue |
    Where-Object {
        $_.FullName -notmatch '\\build\\|\\\.gradle\\|\\\.git\\|\\node_modules\\|atlas-transit'
    }

$changed = 0
$touched = @{}

foreach ($file in $roots) {
    $original = [System.IO.File]::ReadAllText($file.FullName)
    $text = $original

    foreach ($from in $replacements.Keys) {
        $to = $replacements[$from]
        if ($from -eq $to) { continue }
        # Word-boundary match keeps identifiers and code intact: only whole words
        # and dotted/camelCase fragments that stand alone are replaced.
        $text = [regex]::Replace($text, "(?<![\w'])$([regex]::Escape($from))(?![\w])", $to)
    }

    if ($text -ne $original) {
        $utf8 = New-Object System.Text.UTF8Encoding($false)
        [System.IO.File]::WriteAllText($file.FullName, $text, $utf8)
        $changed++
        $touched[$file.Name] = $true
    }
}

Write-Output "Files changed: $changed"
Write-Output ($touched.Keys | Sort-Object)