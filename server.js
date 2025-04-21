const express = require("express");
const swaggerUi = require("swagger-ui-express");
const fs = require("fs");
const app = express();
const port = 3000;

// Charger la documentation générée
const openApiSpec = JSON.parse(fs.readFileSync("./xqual-api.json", "utf-8"));

const options = {
  explorer: true,
  customSiteTitle: "XQual API Documentation",
  customCss: `
    .topbar { background-color: #2c3e50 !important; }
    .swagger-ui .info { margin: 20px 0; }
    .opblock-tag { font-size: 1.2em; }
  `,
};
app.use("/api-docs", (req, res, next) => {
  const auth = { login: "admin", password: "xqual123" };
  const b64auth = (req.headers.authorization || "").split(" ")[1] || "";
  const [login, password] = Buffer.from(b64auth, "base64")
    .toString()
    .split(":");

  if (login === auth.login && password === auth.password) {
    return next();
  }

  res.set("WWW-Authenticate", 'Basic realm="API Docs"');
  res.status(401).send("Authentification requise");
});
// Routes
app.use("/api-docs", swaggerUi.serve, swaggerUi.setup(openApiSpec, options));
app.get("/openapi.json", (req, res) => res.json(openApiSpec));

// Page d'accueil personnalisée
app.get("/", (req, res) =>
  res.send(`
  <html>
    <head>
      <title>XQual API Portal</title>
      <style>
        body { font-family: Arial, sans-serif; margin: 0; padding: 2rem; }
        .container { max-width: 1200px; margin: 0 auto; }
        .header { 
          background: #2c3e50; 
          color: white; 
          padding: 1rem; 
          border-radius: 5px;
          margin-bottom: 2rem;
        }
      </style>
    </head>
    <body>
      <div class="container">
        <div class="header">
          <h1>XQual API Documentation</h1>
          <p>Version: ${openApiSpec.info.version}</p>
        </div>
        <iframe 
          src="/api-docs" 
          style="width: 100%; height: 800px; border: none;"
          title="API Documentation"
        ></iframe>
      </div>
    </body>
  </html>
`)
);

// Démarrer le serveur
app.listen(port, () => {
  console.log(`Serveur démarré sur http://localhost:${port}`);
  console.log(`Documentation disponible sur http://localhost:${port}/api-docs`);
});
