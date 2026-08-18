SELECT format('CREATE DATABASE metabase OWNER %I', current_user)
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'metabase')\gexec
