<?php
$cfg['blowfish_secret'] = 'HunT@Ivan'; /* YOU MUST FILL IN THIS FOR COOKIE AUTH! */
$i = 0;
$i++;
$cfg['Servers'][$i]['auth_type'] = 'cookie';
$cfg['Servers'][$i]['host'] = 'mysql';
$cfg['Servers'][$i]['compress'] = true;
$cfg['Servers'][$i]['AllowNoPassword'] = false;
$cfg['ForceSSL'] = true;
$cfg['Servers'][$i]['ssl'] = false;
$cfg['ExecTimeLimit'] = 0;
// $cfg['PmaAbsoluteUri'] = 'https://o-ogo.ru/myphpadminroute';




// <?php
// $i = 0;
// $i++;
//
// $cfg['Servers'][$i]['auth_type'] = 'cookie';
// $cfg['Servers'][$i]['host'] = 'mysql';
// $cfg['Servers'][$i]['ssl'] = false;
// $cfg['Servers'][$i]['ssl_ca'] = '/etc/ssl/ca.crt';
// $cfg['Servers'][$i]['ssl_cert'] = '/etc/ssl/o-ogo.crt';
// $cfg['Servers'][$i]['ssl_key'] = '/etc/ssl/o-ogo.key';
//
// $cfg['Servers'][$i]['AllowNoPassword'] = false;
// $cfg['UploadDir'] = '';
// $cfg['SaveDir'] = '';


