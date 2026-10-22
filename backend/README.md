# Backend Octomind Progol

Backend serverless (Cognito + DynamoDB + API Gateway + Lambda) para el análisis
de Progol. El motor de análisis vive en la app Android; este backend administra
datos, plantillas personalizadas y el ranking.

## Estructura

```
backend/
├── template.yaml        # CloudFormation (SAM)
├── src/api/app.py       # Lambda única (ruteo)
└── docs/api-contract.md # contrato de la API
```

## Desplegar

```powershell
# 1. Bucket para artefactos (solo la primera vez)
python -m awscli s3 mb s3://octomind-backend-artifacts

# 2. Empaquetar
python -m awscli cloudformation package `
  --template-file template.yaml `
  --s3-bucket octomind-backend-artifacts `
  --output-template-file packaged.yaml

# 3. Desplegar
python -m awscli cloudformation deploy `
  --template-file packaged.yaml `
  --stack-name octomind-backend `
  --capabilities CAPABILITY_IAM CAPABILITY_AUTO_EXPAND
```

## Crear el admin

```powershell
# Crear el usuario admin (o que se registre y luego agregarlo al grupo)
python -m awscli cognito-idp sign-up `
  --client-id <UserPoolClientId> `
  --username ivanigo4043@gmail.com `
  --password 'TuPassword123!' `
  --user-attributes Name=email,Value=ivanigo4043@gmail.com

# Agregarlo al grupo admins
python -m awscli cognito-idp admin-add-user-to-group `
  --user-pool-id <UserPoolId> `
  --username ivanigo4043@gmail.com `
  --group-name admins
```

## Obtener los IDs

```powershell
python -m awscli cloudformation describe-stacks --stack-name octomind-backend `
  --query "Stacks[0].Outputs"
```

## Pendientes (fases siguientes)

- Backoffice web (SPA).
- Notificaciones push (SNS/Pinpoint + FCM).
- Registro de usuarios en `Users` al confirmar registro (trigger Cognito).
