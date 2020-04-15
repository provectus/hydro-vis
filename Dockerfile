FROM python:3.7.4-slim-stretch

RUN apt-get update && \
    apt-get install -y git build-essential

COPY requirements.txt requirements.txt
RUN pip3 install -U pip
RUN pip3 install -r requirements.txt

WORKDIR /app
EXPOSE 5000

COPY . .
