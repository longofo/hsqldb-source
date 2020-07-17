/* Copyright (c) 2001-2008, The HSQL Development Group
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 *
 * Neither the name of the HSQL Development Group nor the names of its
 * contributors may be used to endorse or promote products derived from this
 * software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL HSQL DEVELOPMENT GROUP, HSQLDB.ORG,
 * OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */


package org.hsqldb;

import org.hsqldb.HsqlNameManager.HsqlName;
import org.hsqldb.lib.HsqlArrayList;
import org.hsqldb.lib.Iterator;
import org.hsqldb.lib.IntKeyHashMap;
import org.hsqldb.lib.ArrayUtil;

// fredt@users 20020420 - patch523880 by leptipre@users - VIEW support - modified
// fredt@users 20031227 - remimplementated as compiled query

/**
 * Represents an SQL VIEW based on a SELECT statement.
 *
 * @author leptipre@users
 * @author fredt@users
 * @version 1.8.0
 * @since 1.7.0
 */
class View extends Table {

    Select             viewSelect;
    SubQuery           viewSubQuery;
    private String     statement;
    private HsqlName[] colList;

    /** schema at the time of compilation */
    HsqlName compileTimeSchema;

    /**
     * List of subqueries in this view in order of materialization. Last
     * element is the view itself.
     */
    SubQuery[] viewSubqueries;

    /**
     * Constructor.
     * @param Session
     * @param db database
     * @param name HsqlName of the view
     * @param definition SELECT statement of the view
     * @param columns array of HsqlName column names
     * @throws HsqlException
     */
    View(Session session, Database db, HsqlName name, String definition,
            HsqlName[] columns) throws HsqlException {

        super(db, name, VIEW);

        isReadOnly        = true;
        colList           = columns;
        statement         = trimStatement(definition);
        compileTimeSchema = session.getSchemaHsqlName(null);

        compile(session);
        replaceAsterisksInStatement();

        HsqlName[] schemas = getSchemas();

        for (int i = 0; i < schemas.length; i++) {
            if (db.schemaManager.isSystemSchema(schemas[i])) {
                continue;
            }

            if (!schemas[i].equals(name.schema)) {
                throw Trace.error(Trace.INVALID_SCHEMA_NAME_NO_SUBCLASS);
            }
        }
    }

    /**
     * Returns the SELECT statement trimmed of any terminating SQL
     * whitespace, separators or SQL comments.
     */
    static String trimStatement(String s) throws HsqlException {

        int       position;
        String    str;
        Tokenizer tokenizer = new Tokenizer(s);

        // fredt@users - this establishes the end of the actual statement
        // to get rid of any end semicolon or comment line after the end
        // of statement
        do {
            position = tokenizer.getPosition();
            str      = tokenizer.getString();
        } while (str.length() != 0 || tokenizer.wasValue());

        return s.substring(0, position).trim();
    }

    /**
     * Compiles the SELECT statement and sets up the columns.
     */
    void compile(Session session) throws HsqlException {

        // create the working table
        Parser p = new Parser(session, this.database,
                              new Tokenizer(statement));

        p.setCompilingView();

        int brackets = p.parseOpenBracketsSelect();

        viewSubQuery = p.parseSubquery(brackets, colList, true,
                                       Expression.VIEW);

        p.setAsView(this);

        viewSubqueries = p.getSortedSubqueries();
        viewSelect     = viewSubQuery.select;

        viewSelect.prepareResult(session);

        Result.ResultMetaData metadata = viewSelect.resultMetaData;
        int                   columns  = viewSelect.iResultLen;

        if (super.columnCount == 0) {

            // do not add columns at recompile time
            super.addColumns(metadata, columns);
        }
    }

    /**
     * Returns the SELECT statement for the view.
     */
    String getStatement() {
        return statement;
    }

    /**
     *  is a private helper for replaceAsterisksInStatement, to avoid some code duplication
     */
    private void collectAsteriskPos(final Select select,
                                    IntKeyHashMap asteriskPositions) {

        if (select.asteriskPositions == null) {
            return;
        }

        Iterator asterisks = select.asteriskPositions.keySet().iterator();

        while (asterisks.hasNext()) {
            int pos = asterisks.nextInt();

            asteriskPositions.put(pos, select.asteriskPositions.get(pos));
        }

        // The following is to guarantee the invariant of this class, that the |astersiskPositions|
        // member of the various Select instances properly describe the occurances
        // of Expression.ASTERISK in the statement.
        // Since we are going to replace all those asterisks, we also need to reset the various
        // |astersiskPositions| instances, which is best done here were all non-null
        // Select's need to pass by.
        select.asteriskPositions = null;
    }

    /**
     *  replaces all asterisks in our statement with the actual column list
     *
     *  This way, we ensure what is required by the standard: a view returns a result
     *  which reflects the structure of the underlying tables at the *time of the definition
     *  of the view.
     */
    private void replaceAsterisksInStatement() throws HsqlException {

        IntKeyHashMap asteriskPositions = new IntKeyHashMap();

        // asterisk positions in sub queries
        for (int i = 0; i < viewSubqueries.length; ++i) {

            // collect the occurances of asterisks in the statement
            Select subSelect = viewSubqueries[i].select;

            collectAsteriskPos(subSelect, asteriskPositions);

            // the same for all (possible) UNION SELECTs of the sub select
            if (subSelect.unionArray != null) {

                // start with index 1, not 0 - the first select is the one already covered by subSelect
                for (int u = 1; u < subSelect.unionArray.length; ++u) {
                    collectAsteriskPos(subSelect.unionArray[u],
                                       asteriskPositions);
                }
            }
        }

        int[]    positions = new int[asteriskPositions.size()];
        Iterator it        = asteriskPositions.keySet().iterator();

        for (int i = 0; i < positions.length; i++) {
            positions[i] = it.nextInt();
        }

        ArrayUtil.sortArray(positions);

        StringBuffer expandedStatement = new StringBuffer();
        int          lastPos           = 0;
        String       segment;

        for (int i = 0; i < positions.length; i++) {
            int    pos     = positions[i];
            String colList = (String) asteriskPositions.get(pos);

            if (colList == null) {
                continue;
            }

            segment = statement.substring(lastPos, pos);
            lastPos = statement.indexOf("*", pos) + 1;

            expandedStatement.append(segment);
            expandedStatement.append(' ');
            expandedStatement.append(colList);
            expandedStatement.append(' ');
        }

        segment = statement.substring(lastPos, statement.length());

        expandedStatement.append(segment);

        statement = expandedStatement.toString();
    }

    /**
     * Overridden to disable SET TABLE READONLY DDL for View objects.
     */
    void setDataReadOnly(boolean value) throws HsqlException {
        throw Trace.error(Trace.NOT_A_TABLE);
    }

    /**
     * Returns list of schemas
     */
    HsqlName[] getSchemas() {

        HsqlArrayList list = new HsqlArrayList();

        for (int i = 0; i < viewSubqueries.length; i++) {
            Select select = viewSubqueries[i].select;

            for (; select != null; select = select.unionSelect) {
                TableFilter[] tfilter = select.tFilter;

                for (int j = 0; j < tfilter.length; j++) {
                    list.add(tfilter[j].filterTable.tableName.schema);
                }
            }
        }

        return (HsqlName[]) list.toArray(new HsqlName[list.size()]);
    }

    boolean hasView(View view) {

        if (view == this) {
            return false;
        }

        for (int i = 0; i < viewSubqueries.length; i++) {
            if (viewSubqueries[i].view == view) {
                return true;
            }
        }

        return false;
    }

    /**
     * Returns true if the view references any column of the named table.
     */
    boolean hasTable(Table table) {

        for (int i = 0; i < viewSubqueries.length; i++) {
            Select select = viewSubqueries[i].select;

            for (; select != null; select = select.unionSelect) {
                TableFilter[] tfilter = select.tFilter;

                for (int j = 0; j < tfilter.length; j++) {
                    if (table.equals(tfilter[j].filterTable.tableName)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    /**
     * Returns true if the view references the named column of the named table,
     * otherwise false.
     */
    boolean hasColumn(Table table, String colname) {

        if (hasTable(table)) {
            Expression.Collector coll = new Expression.Collector();

            coll.addAll(viewSubqueries[viewSubqueries.length - 1].select,
                        Expression.COLUMN);

            Iterator it = coll.iterator();

            for (; it.hasNext(); ) {
                Expression e = (Expression) it.next();

                if (colname.equals(e.getBaseColumnName())
                        && table.equals(e.getTableHsqlName())) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Returns true if the view references the named SEQUENCE,
     * otherwise false.
     */
    boolean hasSequence(NumberSequence sequence) {

        Expression.Collector coll = new Expression.Collector();

        coll.addAll(viewSubqueries[viewSubqueries.length - 1].select,
                    Expression.SEQUENCE);

        Iterator it = coll.iterator();

        for (; it.hasNext(); ) {
            Expression e = (Expression) it.next();

            if (e.valueData == sequence) {
                return true;
            }
        }

        return false;
    }
}
