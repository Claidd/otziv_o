<?php
declare(strict_types=1);
set_error_handler(static function (int $severity, string $message): never { throw new RuntimeException($message); });
$checks = [];
$check = static function (string $name, bool $ok) use (&$checks): void {
    if (!$ok) { throw new RuntimeException($name); }
    $checks[] = $name;
};
$check('official PHP 8.3.33 ABI', PHP_VERSION === '8.3.33' && PHP_ZTS === 0 && PHP_DEBUG === 0);
$check('GNU iconv implementation', ICONV_IMPL === 'libiconv' && ICONV_VERSION === '1.18');
$cyrillic = hex2bin('cff0e8e2e5f2');
$utf8 = hex2bin('d09fd180d0b8d0b2d0b5d182');
$check('Windows-1251 to UTF-8', iconv('Windows-1251', 'UTF-8', $cyrillic) === $utf8);
$check('PMA UTF-8 TRANSLIT import', iconv('Windows-1251', 'utf-8//TRANSLIT', $cyrillic) === $utf8);
$check('roundtrip Cyrillic', iconv('UTF-8', 'Windows-1251', $utf8) === $cyrillic);
$check('GNU ASCII accent transliteration table', iconv('UTF-8', 'ASCII//TRANSLIT', "caf\xc3\xa9") === "caf'e");
$check('shared ASCII currency and sharp-s transliteration', iconv('UTF-8', 'ASCII//TRANSLIT', hex2bin('e282acc39f')) === 'EURss');
$check('IGNORE discards invalid UTF-8', iconv('UTF-8', 'UTF-8//IGNORE', "a\xffb") === 'ab');
$check('Unicode character length', iconv_strlen($utf8, 'UTF-8') === 6);
echo json_encode(['result' => 'PASS', 'checks' => $checks], JSON_THROW_ON_ERROR), "\n";
