FROM python:3.14-slim

WORKDIR /app
COPY relay_server.py .

EXPOSE 60005
EXPOSE 8080

CMD ["python3", "-u", "relay_server.py"]