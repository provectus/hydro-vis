#!/usr/bin/env sh

echo "$1"

if [ -z "$1" ]; then
    echo "Choose mode: 'service' or 'worker'"
    exit 1
fi

if [ "$1" = 'service' ]; then
    python app.py
elif [ "$1" = 'worker' ]; then
    celery -A app.celery worker -l info -Q visualization
else
    echo "'$1' mode is incorrect. Supported modes are 'service' or 'worker'"
    exit 1
fi