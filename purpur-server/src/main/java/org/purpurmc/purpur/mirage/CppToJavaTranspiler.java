package org.purpurmc.purpur.mirage;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CppToJavaTranspiler — Basic C++ to Java source transpiler.
 *
 * Converts simple C++ programs into equivalent Java code that can be
 * compiled by the JDK's built-in JavaCompiler. Supports:
 * - #include <iostream> → System.out / System.in
 * - std::cout << x → System.out.print(x)
 * - std::endl → "\n"
 * - int main() → public static void main(String[] args)
 * - Basic types: int, long, double, float, char, bool, string, void
 * - std::string → String
 * - using namespace std → (stripped)
 * - Basic control flow: if/else, for, while, do-while
 * - Vector (basic)
 *
 * This is NOT a full C++ compiler — it handles common patterns for
 * server scripting purposes.
 */
final class CppToJavaTranspiler {

    private CppToJavaTranspiler() {}

    /**
     * Transpile C++ source code to Java source code.
     *
     * @param cppCode The C++ source code
     * @return Equivalent Java source code
     */
    static String transpile(String cppCode) {
        StringBuilder java = new StringBuilder();
        java.append("import java.util.*;\n");
        java.append("import java.io.*;\n\n");
        java.append("public class MirageCppProgram {\n");

        // Process the code line by line, tracking indentation
        String[] lines = cppCode.split("\n");
        int braceDepth = 1; // We're inside the class
        boolean inMain = false;
        boolean mainFound = false;
        StringBuilder methodBody = new StringBuilder();
        int methodBraceDepth = 0;

        // Extract code outside main (global declarations, helper functions)
        StringBuilder globals = new StringBuilder();
        StringBuilder helpers = new StringBuilder();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();

            // Skip preprocessor directives
            if (line.startsWith("#include")) continue;
            if (line.startsWith("#define")) {
                // Convert #define PI 3.14 → static final double PI = 3.14;
                String def = line.substring(7).trim();
                String[] parts = def.split("\\s+", 2);
                if (parts.length == 2) {
                    String name = parts[0];
                    String value = parts[1];
                    String type = value.contains(".") ? "double" : "int";
                    globals.append("    static final ").append(type).append(" ")
                           .append(name).append(" = ").append(value).append(";\n");
                }
                continue;
            }
            if (line.startsWith("#") && !line.startsWith("#pragma")) continue;
            if (line.startsWith("#pragma")) continue;

            // Skip using namespace
            if (line.startsWith("using namespace")) continue;

            // Detect main function
            if (line.matches("int\\s+main\\s*\\(.*\\).*")) {
                mainFound = true;
                inMain = true;
                methodBraceDepth = 0;
                // Check if the opening brace is on the same line
                if (line.contains("{")) methodBraceDepth = 1;
                // Add the main method header
                methodBody.append("    public static void main(String[] args) throws Exception {\n");
                // If there's code after the brace on the same line, process it
                int braceIdx = line.indexOf('{');
                if (braceIdx >= 0 && braceIdx < line.length() - 1) {
                    String rest = line.substring(braceIdx + 1).trim();
                    if (!rest.isEmpty() && !rest.equals("}")) {
                        String converted = convertLine(rest);
                        if (!converted.isEmpty()) methodBody.append("        ").append(converted).append("\n");
                    }
                }
                continue;
            }

            if (inMain) {
                // Track braces
                for (char c : line.toCharArray()) {
                    if (c == '{') methodBraceDepth++;
                    if (c == '}') methodBraceDepth--;
                }

                // Check for end of main
                if (methodBraceDepth <= 0 && line.contains("}")) {
                    // Replace "return 0;" before the closing brace
                    String processed = methodBody.toString()
                        .replace("        return 0;\n", "")
                        .replace("        return 0;", "");
                    methodBody = new StringBuilder(processed);
                    methodBody.append("    }\n");
                    inMain = false;
                    continue;
                }

                // Convert the line
                String converted = convertLine(line);
                if (!converted.isEmpty()) {
                    methodBody.append("        ").append(converted).append("\n");
                }
            } else if (!line.isEmpty()) {
                // Handle helper functions and global declarations
                // Simple function detection: type name(params) {
                if (line.matches("(int|void|double|float|bool|char|string|long|short|auto|std::string).*\\(.*\\).*\\{?")) {
                    // Convert function signature
                    String converted = convertFunctionSignature(line);
                    helpers.append("    ").append(converted);
                    if (!converted.endsWith("{\n")) {
                        helpers.append(" {\n");
                    }
                } else if (line.equals("}")) {
                    helpers.append("    }\n");
                } else {
                    // Global variable or statement
                    String converted = convertLine(line);
                    if (!converted.isEmpty()) {
                        helpers.append("    ").append(converted).append("\n");
                    }
                }
            }
        }

        // If no main found, add empty main
        if (!mainFound) {
            methodBody.append("    public static void main(String[] args) throws Exception {\n");
            methodBody.append("    }\n");
        }

        // Assemble the Java class
        java.append(globals);
        java.append(helpers);
        java.append(methodBody);
        java.append("}\n");

        return java.toString();
    }

    /**
     * Convert a single C++ line to Java.
     */
    private static String convertLine(String line) {
        if (line.isEmpty()) return "";
        // Remove trailing semicolons that Java doesn't need in some contexts
        // but keep them for actual statements

        // std::cout << x << y << std::endl;
        // → System.out.println(x + "" + y);
        if (line.contains("std::cout") || line.contains("cout")) {
            return convertCout(line);
        }

        // std::cin >> x;
        // → x = new Scanner(System.in).nextLine();
        if (line.contains("std::cin") || line.contains("cin")) {
            return convertCin(line);
        }

        // printf(...) → System.out.printf(...)
        if (line.startsWith("printf(") || line.startsWith("printf (")) {
            return line.replace("printf", "System.out.printf");
        }

        // std::string → String
        line = line.replace("std::string", "String");
        line = line.replace("string", "String");

        // std::vector<Type> → ArrayList<Type>
        line = line.replaceAll("std::vector<(.*?)>", "ArrayList<$1>");
        line = line.replaceAll("vector<(.*?)>", "ArrayList<$1>");

        // .push_back( → .add(
        line = line.replace(".push_back(", ".add(");
        // .size() → .size()
        // .pop_back() → .remove(.size()-1)
        line = line.replace(".pop_back()", ".remove(.size()-1)");
        // .at(i) → .get(i)
        line = line.replaceAll("\\.at\\(", ".get(");

        // bool → boolean (but not in "boolean" already)
        line = line.replaceAll("\\bbool\\b", "boolean");

        // std::endl → "\n"
        // Already handled in convertCout, but handle standalone
        line = line.replace("std::endl", "\"\\n\"");

        // return 0; → (remove, void main)
        if (line.trim().equals("return 0;") || line.trim().equals("return 0")) {
            return "";
        }

        // new keyword adjustments
        // String s = "hello"; stays the same
        // int* p = new int[10]; → int[] p = new int[10];
        line = line.replaceAll("(int|long|double|float|char|boolean)\\s*\\*\\s*(\\w+)\\s*=\\s*new\\s+(int|long|double|float|char|boolean)\\[(\\d+)\\]",
            "$1[] $2 = new $1[$4]");

        // // comments stay the same
        // /* */ comments stay the same

        return line;
    }

    /**
     * Convert std::cout << x << y << std::endl;
     * to System.out.println(x + "" + y);
     */
    private static String convertCout(String line) {
        // Remove "std::cout" or "cout"
        line = line.replace("std::cout", "");
        line = line.replace("cout", "");

        // Remove leading << or =
        line = line.replaceAll("^\\s*<<\\s*", "");
        line = line.replaceAll("^\\s*=\\s*", "");

        // Check for endl
        boolean hasEndl = line.contains("std::endl") || line.contains("endl");
        line = line.replace("std::endl", "");
        line = line.replace("endl", "");

        // Split by << and join with +
        String[] parts = line.split("<<");
        StringBuilder result = new StringBuilder();

        for (int i = 0; i < parts.length; i++) {
            String part = parts[i].trim();
            if (part.isEmpty()) continue;

            // Remove trailing semicolon
            if (part.endsWith(";")) part = part.substring(0, part.length() - 1).trim();

            if (result.length() > 0) {
                result.append(" + ");
            }

            // If the part is a string literal or variable, wrap appropriately
            if (part.startsWith("\"") || part.matches("\\d+\\.?\\d*") || part.matches("[a-zA-Z_]\\w*")) {
                result.append(part);
            } else {
                result.append("(").append(part).append(")");
            }
        }

        if (hasEndl) {
            return "System.out.println(" + result + ");";
        } else {
            return "System.out.print(" + result + ");";
        }
    }

    /**
     * Convert std::cin >> x;
     * to x = scanner.next<Type>();
     */
    private static String convertCin(String line) {
        // Remove "std::cin" or "cin"
        line = line.replace("std::cin", "");
        line = line.replace("cin", "");

        // Remove leading >>
        line = line.replaceAll("^\\s*>>\\s*", "");

        // Remove trailing semicolon
        if (line.endsWith(";")) line = line.substring(0, line.length() - 1).trim();

        // Split by >> for multiple inputs
        String[] vars = line.split(">>");
        StringBuilder result = new StringBuilder();
        result.append("{ Scanner _sc = new Scanner(System.in); ");

        for (String var : vars) {
            var = var.trim();
            if (var.isEmpty()) continue;

            // Guess the type from variable name conventions
            // This is a heuristic - in real code the type would be declared
            if (var.matches(".*[iI]nt.*") || var.matches(".*[cC]ount.*") || var.matches(".*[nN]um.*")) {
                result.append(var).append(" = _sc.nextInt(); ");
            } else if (var.matches(".*[dD]ouble.*") || var.matches(".*[fF]loat.*") || var.matches(".*[pP]rice.*")) {
                result.append(var).append(" = _sc.nextDouble(); ");
            } else {
                result.append(var).append(" = _sc.nextLine(); ");
            }
        }

        result.append("}");
        return result.toString();
    }

    /**
     * Convert a C++ function signature to Java.
     */
    private static String convertFunctionSignature(String line) {
        // Remove "std::" prefixes
        line = line.replace("std::string", "String");
        line = line.replace("std::", "");

        // Convert types
        line = line.replaceAll("\\bbool\\b", "boolean");
        line = line.replaceAll("\\bstring\\b", "String");

        // Convert pointer parameters to array parameters
        line = line.replaceAll("(int|long|double|float|char)\\s*\\*", "$1[]");

        // Convert reference parameters (&) - just remove them for simplicity
        line = line.replaceAll("(\\w+)\\s*&", "$1");

        // Add 'static' if not present
        if (!line.contains("static")) {
            line = "static " + line;
        }

        // Ensure it ends with {
        if (!line.endsWith("{") && !line.endsWith("{\n")) {
            // If there's a { somewhere in the line, keep it
            if (line.contains("{")) {
                // Keep as is
            } else {
                line = line.trim();
                if (line.endsWith(")")) {
                    line = line + " {";
                }
            }
        }

        return line;
    }
}
