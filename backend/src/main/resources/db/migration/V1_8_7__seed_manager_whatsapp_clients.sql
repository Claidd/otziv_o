UPDATE managers m
JOIN users u ON u.id = m.user_id
SET m.client_id = CASE LOWER(u.username)
    WHEN 'lika' THEN 'whatsapp_lika'
    WHEN 'vika' THEN 'whatsapp_vika'
    ELSE m.client_id
END
WHERE LOWER(u.username) IN ('lika', 'vika');
