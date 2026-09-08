<?php
$path='/var/www/html/composer.json';
$root=json_decode(file_get_contents($path),true,512,JSON_THROW_ON_ERROR);
$installed=json_decode(file_get_contents('/var/www/html/vendor/composer/installed.json'),true,512,JSON_THROW_ON_ERROR);
$packages=$installed['packages']??$installed;
foreach($packages as $package){$root['require'][$package['name']]=$package['version'];}
$root['require']['twig/twig']='3.27.0';
$root['require']['symfony/cache']='5.4.52';
unset($root['require-dev'],$root['autoload-dev']);
file_put_contents($path,json_encode($root,JSON_PRETTY_PRINT|JSON_UNESCAPED_SLASHES)."\n");
file_put_contents('/tmp/installed-before.json',json_encode($packages,JSON_PRETTY_PRINT|JSON_UNESCAPED_SLASHES)."\n");
echo 'Preserved '.count($packages)." actually distributed packages as explicit requirements\n";
?>
