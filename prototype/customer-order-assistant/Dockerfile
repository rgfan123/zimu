FROM python:3.13-alpine

WORKDIR /app
COPY prototype/customer-order-assistant/app.py /app/app.py
COPY prototype/customer-order-assistant/static /app/static

EXPOSE 8765
CMD ["python", "/app/app.py"]
