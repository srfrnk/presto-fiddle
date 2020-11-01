build:
	current_branch := $(shell git rev-parse --abbrev-ref HEAD)
	docker build -t bde2020/hive:$(current_branch) ./

start:
	docker-compose up

hadoop:
	docker-compose exec datanode bash

hive:
	docker-compose exec hive-server bash -c "/opt/hive/bin/beeline -u jdbc:hive2://localhost:10000"

presto:
	docker-compose exec presto-coordinator bash -c presto

hadoop-load-data:
	docker-compose exec datanode bash -c "/data/load-data.sh"

hive-create-table:
	docker-compose exec hive-server bash -c "/opt/hive/bin/beeline -u jdbc:hive2://localhost:10000 -f /data/create-hive-schema.sql"

presto-query:
	docker-compose exec presto-coordinator bash -c 'presto --execute "select count(*) from hive.default.parquet_test;"'