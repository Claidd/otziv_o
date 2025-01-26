<?php

$container_name = 'my-mysql'; // Имя контейнера MySQL
$db = 'otziv';
$user = 'root';
$pass = 'Rkfql54532111';
$backup_dir = '/docker/backup'; // Путь к директории для бэкапов
$email_to = '2.12nps@mail.ru';
$email_subject = 'Ежедневная копия БД';
$email_body = 'Часть резервной копии базы данных';
$email_from = 'O-Company@o-ogo.ru';
$part_size = 35 * 1024 * 1024; // Максимальный размер каждой части в байтах (10 МБ)

// Создание файла резервной копии
$backup_file = $backup_dir . '/backup_' . date('Y-m-d_H-i-s') . '.sql';
$command = "docker exec $container_name /usr/bin/mysqldump -u$user -p$pass $db > $backup_file";
system($command, $output);

if ($output === 0) {
    echo "Backup successful.\n";

    // Разделение файла на части
    $parts = [];
    $handle = fopen($backup_file, 'rb');
    $index = 0;
    while (!feof($handle)) {
        $part_file = $backup_file . ".part" . $index;
        $parts[] = $part_file;
        $part_handle = fopen($part_file, 'wb');
        fwrite($part_handle, fread($handle, $part_size));
        fclose($part_handle);
        $index++;
    }
    fclose($handle);

    // Отправка каждой части по электронной почте
    foreach ($parts as $part_file) {
        sendEmailWithAttachment($email_to, $email_subject, $email_body, $email_from, $part_file);
    }

    echo "All parts sent successfully.\n";

    // Очистка временных файлов
    foreach ($parts as $part_file) {
        unlink($part_file);
    }
//    unlink($backup_file);
} else {
    echo "Backup failed.\n";
}

function sendEmailWithAttachment($to, $subject, $body, $from, $attachmentPath) {
    $boundary = "PHP-mixed-" . md5(time());

    // Заголовки письма
    $headers = "From: $from\r\n";
    $headers .= "MIME-Version: 1.0\r\n";
    $headers .= "Content-Type: multipart/mixed; boundary=\"$boundary\"\r\n";

    // Тело письма
    $message = "--$boundary\r\n";
    $message .= "Content-Type: text/plain; charset=\"UTF-8\"\r\n";
    $message .= "Content-Transfer-Encoding: 7bit\r\n\r\n";
    $message .= "$body\r\n\r\n";

    // Добавление вложения
    $attachment_content = file_get_contents($attachmentPath);
    $attachment_encoded = chunk_split(base64_encode($attachment_content));
    $message .= "--$boundary\r\n";
    $message .= "Content-Type: application/octet-stream; name=\"" . basename($attachmentPath) . "\"\r\n";
    $message .= "Content-Transfer-Encoding: base64\r\n";
    $message .= "Content-Disposition: attachment; filename=\"" . basename($attachmentPath) . "\"\r\n\r\n";
    $message .= "$attachment_encoded\r\n\r\n";
    $message .= "--$boundary--\r\n";

    // Отправка письма
    $success = mail($to, $subject, $message, $headers);
    if ($success) {
        echo "Email with file " . basename($attachmentPath) . " sent successfully.\n";
    } else {
        echo "Failed to send email with file " . basename($attachmentPath) . ".\n";
    }
}






//$container_name = 'my-mysql'; // замените на имя вашего контейнера
//$db = 'otziv';
//$user = 'root';
//$pass = 'Rkfql54532111';
//$backup_dir = '/docker/backup'; // замените на фактический путь к директории для бэкапов
//$email_to = '2.12nps@mail.ru';
//$email_subject = 'Ежеденевная копия БД';
//$email_body = 'Бэкап БД';
//$email_from = 'O-Company@o-ogo.ru';
//
//// Create backup file
//$backup_file = $backup_dir . '/backup_' . date('Y-m-d_H-i-s') . '.sql';
//$command = "docker exec $container_name /usr/bin/mysqldump -u$user -p$pass $db > $backup_file";
//system($command, $output);
//
//if ($output === 0) {
//    echo "Backup successful.\n";
//
//    // Prepare email headers and body
//    $headers = "From: $email_from\r\n";
//    $headers .= "MIME-Version: 1.0\r\n";
//    $headers .= "Content-Type: multipart/mixed; boundary=\"PHP-mixed-" . md5(time()) . "\"\r\n";
//
//    // Create email body
//    $message = "--PHP-mixed-" . md5(time()) . "\r\n";
//    $message .= "Content-Type: text/plain; charset=\"UTF-8\"\r\n";
//    $message .= "Content-Transfer-Encoding: 7bit\r\n";
//    $message .= "\r\n";
//    $message .= $email_body . "\r\n";
//    $message .= "\r\n";
//
//    // Attach backup file
//    $attachment_content = file_get_contents($backup_file);
//    $attachment_encoded = chunk_split(base64_encode($attachment_content));
//    $message .= "--PHP-mixed-" . md5(time()) . "\r\n";
//    $message .= "Content-Type: application/octet-stream; name=\"" . basename($backup_file) . "\"\r\n";
//    $message .= "Content-Transfer-Encoding: base64\r\n";
//    $message .= "Content-Disposition: attachment; filename=\"" . basename($backup_file) . "\"\r\n";
//    $message .= "\r\n";
//    $message .= $attachment_encoded . "\r\n";
//    $message .= "\r\n";
//    $message .= "--PHP-mixed-" . md5(time()) . "--\r\n";
//
//    // Send email via ssmtp
//    $ssmtp_command = "/usr/sbin/ssmtp -t";
//    $mail_result = mail($email_to, $email_subject, $message, $headers, "-f$email_from");
//
//    if ($mail_result) {
//        echo "Email sent successfully.\n";
//    } else {
//        echo "Failed to send email.\n";
//    }
//} else {
//    echo "Backup failed.\n";
//}
?>




