<?php
$container_name = 'my-mysql'; // замените на имя вашего контейнера
$db = 'otziv';
$user = 'root';
$pass = 'Rkfql54532111';
$backup_dir = '/docker/backup'; // замените на фактический путь к директории для бэкапов
$email_to = '2.12nps@mail.ru';
$email_subject = 'Daily Database Backup';
$email_body = 'Please find the attached database backup.';
$email_from = 'O-Company@o-ogo.ru';

// Create backup file
$backup_file = $backup_dir . '/backup_' . date('Y-m-d_H-i-s') . '.sql';
$command = "docker exec $container_name /usr/bin/mysqldump -u$user -p$pass $db > $backup_file";
system($command, $output);

if ($output === 0) {
    echo "Backup successful.\n";

    // Prepare email headers and body
    $headers = "From: $email_from\r\n";
    $headers .= "MIME-Version: 1.0\r\n";
    $headers .= "Content-Type: multipart/mixed; boundary=\"PHP-mixed-" . md5(time()) . "\"\r\n";

    // Create email body
    $message = "--PHP-mixed-" . md5(time()) . "\r\n";
    $message .= "Content-Type: text/plain; charset=\"UTF-8\"\r\n";
    $message .= "Content-Transfer-Encoding: 7bit\r\n";
    $message .= "\r\n";
    $message .= $email_body . "\r\n";
    $message .= "\r\n";

    // Attach backup file
    $attachment_content = file_get_contents($backup_file);
    $attachment_encoded = chunk_split(base64_encode($attachment_content));
    $message .= "--PHP-mixed-" . md5(time()) . "\r\n";
    $message .= "Content-Type: application/octet-stream; name=\"" . basename($backup_file) . "\"\r\n";
    $message .= "Content-Transfer-Encoding: base64\r\n";
    $message .= "Content-Disposition: attachment; filename=\"" . basename($backup_file) . "\"\r\n";
    $message .= "\r\n";
    $message .= $attachment_encoded . "\r\n";
    $message .= "\r\n";
    $message .= "--PHP-mixed-" . md5(time()) . "--\r\n";

    // Send email via ssmtp
    $ssmtp_command = "/usr/sbin/ssmtp -t";
    $mail_result = mail($email_to, $email_subject, $message, $headers, "-f$email_from");

    if ($mail_result) {
        echo "Email sent successfully.\n";
    } else {
        echo "Failed to send email.\n";
    }
} else {
    echo "Backup failed.\n";
}
?>




