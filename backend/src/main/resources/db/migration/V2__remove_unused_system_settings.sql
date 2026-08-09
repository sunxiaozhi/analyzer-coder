DELETE FROM system_settings
WHERE setting_key IN (
    'embeddingModel',
    'llmProvider',
    'maxSearchResults',
    'excludedPatterns',
    'backupRetentionDays'
);
