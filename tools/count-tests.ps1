Set-Location 'd:\code-android\MultiSuperPlayer'
function Stat($variant) {
    $files = Get-ChildItem -Recurse -Path . -Filter 'TEST-*.xml' | Where-Object { $_.FullName -match "test-results\\$variant" }
    $t = 0; $f = 0; $e = 0; $cases = 0; $skip = 0
    foreach ($x in $files) {
        $r = [System.IO.File]::ReadAllText($x.FullName)
        $tag = [regex]::Match($r, '<testsuite\s[^>]*>').Value
        $t += [int][regex]::Match($tag, 'tests="(\d+)"').Groups[1].Value
        $f += [int][regex]::Match($tag, 'failures="(\d+)"').Groups[1].Value
        $e += [int][regex]::Match($tag, 'errors="(\d+)"').Groups[1].Value
        $skip += [int][regex]::Match($tag, 'skipped="(\d+)"').Groups[1].Value
        $cases += ([regex]::Matches($r, '<testcase ')).Count
    }
    "$variant : suites=$($files.Count) tests=$t failures=$f errors=$e skipped=$skip testcases=$cases"
}
Stat 'testDebugUnitTest'
Stat 'testReleaseUnitTest'
