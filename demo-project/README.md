# Demo Project for Forge Build System

Este es un proyecto de demostración que muestra las capacidades del sistema de compilación Forge Build System.

## Estructura del Proyecto

```
demo-project/
├── src/
│   ├── main/
│   │   └── java/
│   │       └── com/
│   │           └── example/
│   │               ├── Calculator.java
│   │               └── MathUtils.java
│   └── test/
│       └── java/
│           └── com/
│               └── example/
│                   └── CalculatorTest.java
├── forge.config.json
└── README.md
```

## Características Demostradas

### ✅ Compilación Incremental
- Forge analiza solo los archivos modificados
- Optimización automática de tiempo de compilación

### ✅ Análisis de Proyecto
- Detección automática de estructura de paquetes
- Análisis de complejidad de código
- Identificación de archivos de prueba

### ✅ Resolución de Dependencias
- Descarga automática desde Maven Central
- Cache local para mejor rendimiento
- Soporte para múltiples tipos de dependencias

### ✅ Empaquetado de Artefactos
- Generación automática de JAR principal
- Creación de JAR de fuentes
- Generación de javadoc

### ✅ Ejecución de Tests
- Detección automática de archivos de prueba
- Ejecución en paralelo para mejor rendimiento
- Reporte detallado de resultados

## Uso con Forge

1. Asegúrate de tener Forge Build System compilado
2. Ejecuta el comando Forge desde la raíz del proyecto:
   ```bash
   java -jar forge.jar build
   ```

## Clases Incluidas

### Calculator.java
- Operaciones matemáticas básicas
- Manejo de errores (división por cero)
- Ejemplo de código con múltiples métodos

### MathUtils.java
- Utilidades matemáticas avanzadas
- Algoritmos de números primos
- Cálculos de factorial y potencias

### CalculatorTest.java
- Suite de pruebas unitarias
- Validación de casos edge
- Demostración de manejo de excepciones

## Dependencias del Proyecto

- JUnit 4.13.2 (para testing)
- SLF4J 1.7.36 (para logging)
- Logback 1.2.12 (implementación de logging)

Este proyecto demuestra las capacidades completas del sistema Forge Build System en un entorno real de desarrollo Java.