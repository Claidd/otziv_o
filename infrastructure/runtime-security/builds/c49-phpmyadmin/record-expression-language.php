<?php
// C49: record the official symfony/expression-language v5.4.54 in Composer's installed metadata.
// Only the expression-language entries change; every other byte of both files is kept.
// Writing through the existing paths keeps their inode, owner and read-only mode.
declare(strict_types=1);

const OLD_REFERENCE = 'a784b66edc4c151eb05076d04707906ee2c209a9';
const NEW_REFERENCE = 'f371786ec76ca4bed6a7ec03802ba2525f6714db';

function fail(string $message): never
{
    fwrite(STDERR, $message . "\n");
    exit(1);
}

function replaceExactly(string $text, string $from, string $to, int $expected = 1): string
{
    $result = str_replace($from, $to, $text, $count);
    if ($count !== $expected) {
        fail("expected {$expected} occurrence(s) of {$from}, found {$count}");
    }
    return $result;
}

function block(string $text, string $start, string $end): array
{
    $from = strpos($text, $start);
    if ($from === false || strpos($text, $start, $from + 1) !== false) {
        fail("expected one block starting with {$start}");
    }
    $to = strpos($text, $end, $from);
    if ($to === false) {
        fail("block {$start} has no end {$end}");
    }
    return [$from, $to];
}

$directory = $argv[1] ?? fail('composer metadata directory required');

$jsonPath = $directory . '/installed.json';
$json = file_get_contents($jsonPath);
[$from, $to] = block($json, '"name": "symfony/expression-language",', '"install-path": "../symfony/expression-language"');
$entry = substr($json, $from, $to - $from);
$entry = replaceExactly($entry, '"version": "v5.4.45",', '"version": "v5.4.54",');
$entry = replaceExactly($entry, '"version_normalized": "5.4.45.0",', '"version_normalized": "5.4.54.0",');
$entry = replaceExactly($entry, OLD_REFERENCE, NEW_REFERENCE, 3);
$entry = replaceExactly($entry, "\"symfony/cache\": \"^4.4|^5.0|^6.0\",\n",
    "\"symfony/cache\": \"^4.4|^5.0|^6.0\",\n                \"symfony/polyfill-php80\": \"^1.16\",\n");
$entry = replaceExactly($entry, '"time": "2024-10-04T14:55:40+00:00",', '"time": "2026-10-09T11:21:43+00:00",');
$entry = replaceExactly($entry, 'symfony/expression-language/tree/v5.4.45"', 'symfony/expression-language/tree/v5.4.54"');
$json = substr($json, 0, $from) . $entry . substr($json, $to);
json_decode($json, true, 512, JSON_THROW_ON_ERROR);
if (file_put_contents($jsonPath, $json) !== strlen($json)) {
    fail('installed.json write failed');
}

$phpPath = $directory . '/installed.php';
$php = file_get_contents($phpPath);
[$from, $to] = block($php, "'symfony/expression-language' => array(", "'dev_requirement' => false,");
$entry = substr($php, $from, $to - $from);
$entry = replaceExactly($entry, "'pretty_version' => 'v5.4.45',", "'pretty_version' => 'v5.4.54',");
$entry = replaceExactly($entry, "'version' => '5.4.45.0',", "'version' => '5.4.54.0',");
$entry = replaceExactly($entry, "'reference' => '" . OLD_REFERENCE . "',", "'reference' => '" . NEW_REFERENCE . "',");
$php = substr($php, 0, $from) . $entry . substr($php, $to);
if (file_put_contents($phpPath, $php) !== strlen($php)) {
    fail('installed.php write failed');
}
