const javaParser = require("./parser");

// Mots-clés par catégorie pour deviner dynamiquement les tags
const keywordCategories = {
  authentication: ["auth", "login", "logout", "sso", "token", "register"],
  users: ["user", "profile", "license", "info"],
  teams: ["team"],
  suts: ["sut"],
  requirements: ["requirement"],
  specifications: ["specification"],
  tests: ["test"],
  "test-attributes": ["attribute"],
  "test-cases": ["case"],
  campaigns: ["campaign"],
  bugs: ["bug"],
  folders: ["folder"],
  "custom-fields": ["custom", "field"],
  reports: ["report"],
  misc: ["log", "setting", "database", "check"],
};

// Devine le tag en fonction du nom de l'endpoint
const guessTagFromEndpoint = (endpointName) => {
  const lowerEndpoint = endpointName.toLowerCase();
  for (const [tag, keywords] of Object.entries(keywordCategories)) {
    if (keywords.some((kw) => lowerEndpoint.includes(kw))) {
      return tag;
    }
  }
  return "misc";
};

const generateSpec = (endpoints, javaCode) => {
  const paths = {};

  endpoints.forEach((endpoint) => {
    const parameters = javaParser
      .extractParameters(javaCode, endpoint)
      .filter((p) => p !== "command")
      .map((param) => ({
        name: param,
        in: "query",
        schema: { type: "string" },
        description: `Paramètre détecté pour ${endpoint}`,
      }));

    const method = javaParser.extractMethod(javaCode, endpoint);
    const tag = guessTagFromEndpoint(endpoint);

    const pathItem = {
      [method]: {
        tags: [tag],
        summary: `Endpoint: ${endpoint}`,
        description: `Endpoint détecté dans le code source`,
        operationId: endpoint.replace(/[^a-zA-Z0-9_]/g, "_"),
        parameters: [
          {
            name: "command",
            in: "query",
            required: true,
            schema: {
              type: "string",
              enum: [endpoint],
              example: endpoint,
            },
            description: "Commande principale",
          },
          ...parameters,
        ],
        responses: {
          200: {
            description: "Réponse standard",
            content: {
              "application/json": {
                schema: {
                  type: "object",
                  properties: {
                    result: { type: "string" },
                    message: { type: "string" },
                  },
                },
              },
            },
          },
          401: {
            description: "Non autorisé",
            content: {
              "application/json": { schema: { type: "object" } },
            },
          },
        },
      },
    };

    const pathKey = `/${endpoint
      .replace(/[A-Z]/g, (m) => "-" + m.toLowerCase())
      .replace(/^get-?/, "")}`;

    paths[pathKey] = pathItem;
  });

  return {
    openapi: "3.0.3",
    info: {
      title: "Documentation API XQual",
      version: "1.0.0",
      description: "Généré automatiquement à partir de CServer.java",
    },
    servers: [{ url: "/xapiserver" }],
    paths,
  };
};

module.exports = { generateSpec };
