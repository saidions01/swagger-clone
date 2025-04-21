const { program } = require("commander");
const fs = require("fs");
const prettier = require("prettier");
const { generateSpec } = require("./openapi");
const javaParser = require("./parser");

program
  .version("1.0.0")
  .requiredOption("-i, --input <path>", "Fichier Java source")
  .option("-o, --output <path>", "Fichier de sortie OpenAPI", "openapi.json")
  .action(async (options) => {
    try {
      const javaCode = fs.readFileSync(options.input, "utf-8");
      const endpoints = javaParser.extractEndpoints(javaCode);
      const spec = generateSpec(endpoints, javaCode);

      const formatted = await prettier.format(JSON.stringify(spec), {
        parser: "json",
      });

      fs.writeFileSync(options.output, formatted);
      console.log(`Documentation générée: ${options.output}`);
    } catch (error) {
      console.error("Erreur:", error.message);
      process.exit(1);
    }
  });

program.parse(process.argv);
