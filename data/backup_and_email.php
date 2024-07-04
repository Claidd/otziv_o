<?php
$container_name = 'my-mysql'; // замените на имя вашего контейнера
$db = 'otziv';
$user = 'hunt';
$pass = 'Rkfql54532111';
$backup_dir = '/docker/backup'; // замените на фактический путь к директории для бэкапов
$email_to = '2.12nps@mail.ru';
$email_subject = 'Daily Database Backup';
$email_body = 'Please find the attached database backup.';
$email_from = 'iquest38@mail.ru';

if (!file_exists($backup_dir)) {
    mkdir($backup_dir, 0777, true);
}

// Create backup file
$backup_file = $backup_dir . '/backup_' . date('Y-m-d_H-i-s') . '.sql';
$command = "docker exec $container_name /usr/bin/mysqldump -u$user -p$pass $db > $backup_file";
system($command, $output);

if ($output === 0) {
    echo "Backup successful.\n";

    // Send email with attachment
    $boundary = md5(time());
    $headers = "From: $email_from\r\n";
    $headers .= "MIME-Version: 1.0\r\n";
    $headers .= "Content-Type: multipart/mixed; boundary=\"$boundary\"\r\n";

    $message = "--$boundary\r\n";
    $message .= "Content-Type: text/plain; charset=\"UTF-8\"\r\n";
    $message .= "Content-Transfer-Encoding: 7bit\r\n";
    $message .= "\r\n";
    $message .= $email_body . "\r\n";
    $message .= "--$boundary\r\n";
    $message .= "Content-Type: application/octet-stream; name=\"" . basename($backup_file) . "\"\r\n";
    $message .= "Content-Transfer-Encoding: base64\r\n";
    $message .= "Content-Disposition: attachment; filename=\"" . basename($backup_file) . "\"\r\n";
    $message .= "\r\n";
    $message .= chunk_split(base64_encode(file_get_contents($backup_file))) . "\r\n";
    $message .= "--$boundary--\r\n";

    if (mail($email_to, $email_subject, $message, $headers)) {
        echo "Email sent successfully.\n";
    } else {
        echo "Failed to send email.\n";
    }
} else {
    echo "Backup failed.\n";
}
?>


dnl define(`confDOMAIN_NAME', `o-ogo.ru')dnl
define(`SMART_HOST', `smtp.example.com')dnl
define(`RELAY_MAILER_ARGS', `TCP $h 587')dnl
define(`ESMTP_MAILER_ARGS', `TCP $h 587')dnl