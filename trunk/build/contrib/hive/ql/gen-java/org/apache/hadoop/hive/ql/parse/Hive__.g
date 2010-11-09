lexer grammar Hive;
@header {package org.apache.hadoop.hive.ql.parse;}

// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 759
KW_TRUE : 'TRUE';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 760
KW_FALSE : 'FALSE';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 761
KW_ALL : 'ALL';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 762
KW_AND : 'AND';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 763
KW_OR : 'OR';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 764
KW_NOT : 'NOT';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 765
KW_LIKE : 'LIKE';

// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 767
KW_ASC : 'ASC';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 768
KW_DESC : 'DESC';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 769
KW_ORDER : 'ORDER';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 770
KW_BY : 'BY';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 771
KW_GROUP : 'GROUP';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 772
KW_WHERE : 'WHERE';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 773
KW_FROM : 'FROM';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 774
KW_AS : 'AS';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 775
KW_SELECT : 'SELECT';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 776
KW_DISTINCT : 'DISTINCT';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 777
KW_INSERT : 'INSERT';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 778
KW_OVERWRITE : 'OVERWRITE';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 779
KW_OUTER : 'OUTER';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 780
KW_JOIN : 'JOIN';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 781
KW_LEFT : 'LEFT';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 782
KW_RIGHT : 'RIGHT';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 783
KW_FULL : 'FULL';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 784
KW_ON : 'ON';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 785
KW_PARTITION : 'PARTITION';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 786
KW_PARTITIONS : 'PARTITIONS';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 787
KW_TABLE: 'TABLE';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 788
KW_TABLES: 'TABLES';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 789
KW_SHOW: 'SHOW';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 790
KW_DIRECTORY: 'DIRECTORY';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 791
KW_LOCAL: 'LOCAL';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 792
KW_TRANSFORM : 'TRANSFORM';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 793
KW_USING: 'USING';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 794
KW_CLUSTER: 'CLUSTER';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 795
KW_UNION: 'UNION';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 796
KW_LOAD: 'LOAD';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 797
KW_DATA: 'DATA';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 798
KW_INPATH: 'INPATH';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 799
KW_IS: 'IS';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 800
KW_NULL: 'NULL';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 801
KW_CREATE: 'CREATE';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 802
KW_EXTERNAL: 'EXTERNAL';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 803
KW_ALTER: 'ALTER';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 804
KW_DESCRIBE: 'DESCRIBE';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 805
KW_DROP: 'DROP';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 806
KW_RENAME: 'RENAME';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 807
KW_TO: 'TO';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 808
KW_COMMENT: 'COMMENT';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 809
KW_BOOLEAN: 'BOOLEAN';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 810
KW_TINYINT: 'TINYINT';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 811
KW_INT: 'INT';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 812
KW_BIGINT: 'BIGINT';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 813
KW_FLOAT: 'FLOAT';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 814
KW_DOUBLE: 'DOUBLE';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 815
KW_DATE: 'DATE';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 816
KW_DATETIME: 'DATETIME';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 817
KW_TIMESTAMP: 'TIMESTAMP';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 818
KW_STRING: 'STRING';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 819
KW_ARRAY: 'ARRAY';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 820
KW_MAP: 'MAP';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 821
KW_PARTITIONED: 'PARTITIONED';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 822
KW_CLUSTERED: 'CLUSTERED';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 823
KW_SORTED: 'SORTED';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 824
KW_INTO: 'INTO';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 825
KW_BUCKETS: 'BUCKETS';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 826
KW_ROW: 'ROW';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 827
KW_FORMAT: 'FORMAT';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 828
KW_DELIMITED: 'DELIMITED';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 829
KW_FIELDS: 'FIELDS';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 830
KW_TERMINATED: 'TERMINATED';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 831
KW_COLLECTION: 'COLLECTION';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 832
KW_ITEMS: 'ITEMS';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 833
KW_KEYS: 'KEYS';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 834
KW_LINES: 'LINES';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 835
KW_STORED: 'STORED';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 836
KW_SEQUENCEFILE: 'SEQUENCEFILE';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 837
KW_LOCATION: 'LOCATION';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 838
KW_TABLESAMPLE: 'TABLESAMPLE';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 839
KW_BUCKET: 'BUCKET';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 840
KW_OUT: 'OUT';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 841
KW_OF: 'OF';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 842
KW_CAST: 'CAST';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 843
KW_ADD: 'ADD';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 844
KW_REPLACE: 'REPLACE';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 845
KW_COLUMNS: 'COLUMNS';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 846
KW_RLIKE: 'RLIKE';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 847
KW_REGEXP: 'REGEXP';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 848
KW_TEMPORARY: 'TEMPORARY';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 849
KW_FUNCTION: 'FUNCTION';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 850
KW_EXPLAIN: 'EXPLAIN';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 851
KW_EXTENDED: 'EXTENDED';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 852
KW_SERIALIZER: 'SERIALIZER';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 853
KW_WITH: 'WITH';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 854
KW_PROPERTIES: 'SERDEPROPERTIES';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 855
KW_LIMIT: 'LIMIT';

// Operators

// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 859
DOT : '.'; // generated as a part of Number rule
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 860
COLON : ':' ;
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 861
COMMA : ',' ;
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 862
SEMICOLON : ';' ;

// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 864
LPAREN : '(' ;
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 865
RPAREN : ')' ;
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 866
LSQUARE : '[' ;
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 867
RSQUARE : ']' ;

// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 869
EQUAL : '=';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 870
NOTEQUAL : '<>';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 871
LESSTHANOREQUALTO : '<=';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 872
LESSTHAN : '<';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 873
GREATERTHANOREQUALTO : '>=';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 874
GREATERTHAN : '>';

// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 876
DIVIDE : '/';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 877
PLUS : '+';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 878
MINUS : '-';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 879
STAR : '*';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 880
MOD : '%';

// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 882
AMPERSAND : '&';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 883
TILDE : '~';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 884
BITWISEOR : '|';
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 885
BITWISEXOR : '^';

// LITERALS
// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 888
fragment
Letter
    : 'a'..'z' | 'A'..'Z'
    ;

// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 893
fragment
HexDigit
    : 'a'..'f' | 'A'..'F' 
    ;

// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 898
fragment
Digit
    :
    '0'..'9'
    ;

// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 904
fragment
Exponent
    :
    'e' ( PLUS|MINUS )? (Digit)+
    ;

// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 910
StringLiteral
    :
    '\'' (~'\'')* '\'' ( '\'' (~'\'')* '\'' )*
    ;

// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 915
CharSetLiteral
    :    
    StringLiteral 
    | '0' 'X' (HexDigit|Digit)+
    ;

// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 921
Number
    :
    (Digit)+ ( DOT (Digit)* (Exponent)? | Exponent)?
    ;

// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 926
Identifier
    :
    (Letter | Digit) (Letter | Digit | '_')*
    ;

// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 931
CharSetName
    :
    '_' (Letter | Digit | '_' | '-' | '.' | ':' )+
    ;

// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 936
WS  :  (' '|'\r'|'\t'|'\n') {$channel=HIDDEN;}
    ;

// $ANTLR src "/mnt/data/yzhang/workspace/phaoop/src/contrib/hive/ql/src/java/org/apache/hadoop/hive/ql/parse/Hive.g" 939
COMMENT
  : '--' (~('\n'|'\r'))*
    { $channel=HIDDEN; }
  ;


