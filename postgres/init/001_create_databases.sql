-- Cria databases e roles isolados para app e spicedb. Nenhum dos dois
-- roles tem CONNECT na database do outro.

CREATE ROLE app_user LOGIN PASSWORD 'app_pw';
CREATE DATABASE app OWNER app_user;

CREATE ROLE spicedb_user LOGIN PASSWORD 'spicedb_pw';
CREATE DATABASE spicedb OWNER spicedb_user;

REVOKE ALL ON DATABASE app FROM PUBLIC;
REVOKE ALL ON DATABASE spicedb FROM PUBLIC;
GRANT ALL PRIVILEGES ON DATABASE app TO app_user;
GRANT ALL PRIVILEGES ON DATABASE spicedb TO spicedb_user;
